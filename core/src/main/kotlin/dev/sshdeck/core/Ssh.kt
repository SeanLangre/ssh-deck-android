package dev.sshdeck.core

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** Colour tags of the desktop's output panel: blue command, green output, red error. */
enum class OutputTag { CMD, OUTPUT, ERROR }

fun interface OutputSink {
    fun emit(tag: OutputTag, text: String)
}

/** Remembered host key fingerprints, keyed by `host:port`. */
interface KnownHosts {
    operator fun get(hostId: String): String?
    operator fun set(hostId: String, fingerprint: String)
    fun remove(hostId: String)
}

class HostKeyChangedException(val hostId: String, val expected: String, val actual: String) :
    IOException("Host key for $hostId changed!\nExpected $expected\nGot      $actual")

/** Trust on first use, like the desktop (AutoAddPolicy + known_hosts); a changed key is rejected. */
private class TofuVerifier(private val knownHosts: KnownHosts, private val onTrusted: (String) -> Unit) : HostKeyVerifier {
    @Volatile var mismatch: HostKeyChangedException? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = hostId(hostname, port)
        val actual = hostKeyFingerprint(key)
        val expected = knownHosts[id]
        if (expected == null) {
            knownHosts[id] = actual
            onTrusted(actual)
            return true
        }
        if (expected == actual) return true
        mismatch = HostKeyChangedException(id, expected, actual)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        listOfNotNull(knownHosts[hostId(hostname, port)]?.substringBefore(' '))
}

fun hostId(host: String, port: Int) = "$host:$port"

/** `ecdsa-sha2-nistp256 SHA256:...`, the same fingerprint `ssh` prints. */
fun hostKeyFingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "${KeyType.fromKey(key)} SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
}

/** OpenSSH `authorized_keys` line for [key]. */
fun authorizedKeysLine(key: PublicKey, comment: String): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    return "${KeyType.fromKey(key)} ${Base64.getEncoder().encodeToString(blob)} $comment"
}

/** Lets another thread stop a run by closing its connection. */
class Cancellation {
    @Volatile var isCancelled = false
        private set
    @Volatile private var resource: Closeable? = null

    fun cancel() {
        isCancelled = true
        runCatching { resource?.close() }
    }

    internal fun attach(c: Closeable) {
        resource = c
        if (isCancelled) runCatching { c.close() }
    }
}

/** Where a `#@download` lands on this device. */
interface DownloadSink {
    /** Human-readable destination, for the log. */
    val description: String

    /** Open a file for writing; [rel] is its path under the destination, empty for a single-file download. */
    fun open(rel: List<String>): OutputStream
}

/**
 * Runs commands and downloads over SSH, logging the same messages as the desktop's
 * run_ssh_command / run_sftp_download. Calls block; run them off the main thread.
 */
class SshRunner(
    private val keyPair: () -> KeyPair,
    private val knownHosts: KnownHosts,
    private val connectTimeoutMs: Int = 15_000,
) {
    fun runCommand(server: Server, command: String, out: OutputSink, cancellation: Cancellation = Cancellation()) {
        val log = StrippedSink(out)
        val start = System.nanoTime()
        log.emit(OutputTag.CMD, "\n[${timestamp()}] ${server.name} >> $command\n")
        try {
            connect(server, log, cancellation).use { client ->
                client.startSession().use { session ->
                    // With a pty, stderr is merged into stdout.
                    session.allocateDefaultPTY()
                    val cmd = session.exec(buildExecCommand(command, server.loginShell))
                    // The reader keeps multi-byte characters intact across reads.
                    val reader = InputStreamReader(cmd.inputStream, Charsets.UTF_8)
                    val chunk = CharArray(4096)
                    val buf = StringBuilder()
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        buf.append(chunk, 0, n)
                        var nl = buf.indexOf("\n")
                        while (nl >= 0) {
                            log.emit(OutputTag.OUTPUT, buf.substring(0, nl + 1))
                            buf.delete(0, nl + 1)
                            nl = buf.indexOf("\n")
                        }
                    }
                    if (buf.isNotEmpty()) log.emit(OutputTag.OUTPUT, "$buf\n")
                    cmd.join()
                    val code = cmd.exitStatus ?: -1
                    if (code == 0) {
                        log.emit(OutputTag.OUTPUT, "\n✓ Completed successfully (${elapsed(start)})\n")
                    } else {
                        log.emit(OutputTag.ERROR, "\n✗ FAILED — exit code $code (${elapsed(start)})\n")
                    }
                }
            }
        } catch (e: Exception) {
            log.emit(OutputTag.ERROR, "\n${errorMessage(e, server, cancellation)} (${elapsed(start)})\n")
        }
    }

    /** Copy a remote file or folder (recursively) into [sink]. */
    fun download(server: Server, remote: String, sink: DownloadSink, out: OutputSink, cancellation: Cancellation = Cancellation()) {
        val log = StrippedSink(out)
        val start = System.nanoTime()
        log.emit(OutputTag.CMD, "\n[${timestamp()}] ${server.name} download\n  $remote\n  -> ${sink.description}\n")
        var files = 0
        var bytes = 0L

        fun fetch(sftp: SFTPClient, path: String, rel: List<String>) {
            if (cancellation.isCancelled) throw IOException("Cancelled")
            val attrs = sftp.stat(path)
            if (attrs.type == FileMode.Type.DIRECTORY) {
                for (entry in sftp.ls(path).sortedBy { it.name }) {
                    if (entry.name == "." || entry.name == "..") continue
                    fetch(sftp, "$path/${entry.name}", rel + entry.name)
                }
                return
            }
            sftp.open(path).use { file ->
                file.ReadAheadRemoteFileInputStream(16).use { input -> sink.open(rel).use { input.copyTo(it) } }
            }
            files++
            bytes += attrs.size
            val name = if (rel.isEmpty()) path.substringAfterLast('/') else rel.joinToString("/")
            log.emit(OutputTag.OUTPUT, "  $name (${"%.0f".format(Locale.ROOT, attrs.size / 1024.0)} KB)\n")
        }

        try {
            connect(server, log, cancellation).use { client ->
                client.newSFTPClient().use { sftp -> fetch(sftp, toSftpPath(remote), emptyList()) }
            }
            val mb = "%.1f".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
            log.emit(OutputTag.OUTPUT, "\n✓ Downloaded $files files ($mb MB) to ${sink.description} (${elapsed(start)})\n")
        } catch (e: Exception) {
            log.emit(OutputTag.ERROR, "\n${errorMessage(e, server, cancellation)} (${elapsed(start)})\n")
        }
    }

    /** Forget a host's key, e.g. after it was legitimately reinstalled. */
    fun forgetHostKey(server: Server) = knownHosts.remove(hostId(server.host, server.port))

    private fun connect(server: Server, log: OutputSink, cancellation: Cancellation): SSHClient {
        val verifier = TofuVerifier(knownHosts) { log.emit(OutputTag.CMD, "Trusted new host key $it\n") }
        val client = SSHClient(DefaultConfig())
        cancellation.attach(client)
        client.connectTimeout = connectTimeoutMs
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(server.host, server.port)
            client.authPublickey(server.username, KeyPairWrapper(keyPair()))
        } catch (e: Exception) {
            runCatching { client.close() }
            throw verifier.mismatch ?: e
        }
        return client
    }

    private fun errorMessage(e: Exception, server: Server, cancellation: Cancellation): String = when {
        cancellation.isCancelled -> "Cancelled"
        e is HostKeyChangedException -> "Error: ${e.message}"
        e is UserAuthException ->
            "Error: authentication failed for ${server.username}@${server.host}. " +
                "Add this device's public key to the server's authorized_keys."
        else -> "Error: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun elapsed(startNanos: Long) = formatElapsed((System.nanoTime() - startNanos) / 1_000_000_000)

    private fun timestamp() = LocalDateTime.now().format(TIMESTAMP)

    /** Strips ANSI codes like the desktop's update_ui, dropping chunks that become empty. */
    private class StrippedSink(private val out: OutputSink) : OutputSink {
        override fun emit(tag: OutputTag, text: String) {
            val clean = stripAnsi(text)
            if (clean.isNotEmpty()) out.emit(tag, clean)
        }
    }

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
