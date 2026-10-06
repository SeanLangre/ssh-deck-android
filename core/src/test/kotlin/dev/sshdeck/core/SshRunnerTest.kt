package dev.sshdeck.core

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Runs SshRunner against a real (embedded) SSH server with exec and SFTP. */
class SshRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clientKey: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private lateinit var sshd: SshServer
    private lateinit var sftpRoot: File
    private val hosts = object : KnownHosts {
        val map = mutableMapOf<String, String>()
        override fun get(hostId: String) = map[hostId]
        override fun set(hostId: String, fingerprint: String) { map[hostId] = fingerprint }
        override fun remove(hostId: String) { map.remove(hostId) }
    }
    private val runner = SshRunner({ clientKey }, hosts)
    private val server get() = Server(name = "test", host = "127.0.0.1", port = sshd.port, username = "dev", loginShell = false)

    @Before fun startServer() {
        sftpRoot = tmp.newFolder("remote")
        sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPublickeyAuthenticator { _, key, _ -> KeyUtils.compareKeys(key, clientKey.public) }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(sftpRoot.toPath())
            start()
        }
    }

    @After fun stopServer() = sshd.stop(true)

    private fun run(command: String, target: Server = server): List<Pair<OutputTag, String>> {
        val lines = mutableListOf<Pair<OutputTag, String>>()
        runner.runCommand(target, command, { tag, text -> lines += tag to text })
        return lines
    }

    @Test fun streamsOutputAndReportsSuccess() {
        val lines = run("printf 'one\\ntwo\\n'; printf 'tail-no-newline'")
        assertEquals(OutputTag.CMD, lines[0].first)
        assertTrue(lines[0].second, lines[0].second.contains("test >> printf"))
        assertTrue(lines[1].second, Regex("Trusted new host key \\S+ SHA256:[A-Za-z0-9+/]{43}\n").matches(lines[1].second))
        val output = lines.filter { it.first == OutputTag.OUTPUT }.map { it.second }
        assertEquals(listOf("one\n", "two\n", "tail-no-newline\n"), output.dropLast(1))
        assertTrue(output.last(), output.last().startsWith("\n✓ Completed successfully ("))
    }

    @Test fun reportsExitCode() {
        val last = run("exit 3").last()
        assertEquals(OutputTag.ERROR, last.first)
        assertTrue(last.second, last.second.startsWith("\n✗ FAILED — exit code 3 ("))
    }

    @Test fun rejectsChangedHostKey() {
        run("true")
        val id = hostId("127.0.0.1", sshd.port)
        hosts[id] = "ecdsa-sha2-nistp256 SHA256:not-the-real-key"
        val last = run("true").last()
        assertEquals(OutputTag.ERROR, last.first)
        assertTrue(last.second, last.second.contains("Host key for $id changed!"))

        runner.forgetHostKey(server)
        assertTrue(run("true").last().second.contains("Completed successfully"))
    }

    @Test fun explainsAuthFailure() {
        sshd.setPublickeyAuthenticator { _, _, _ -> false }
        val last = run("true").last()
        assertTrue(last.second, last.second.contains("authentication failed for dev@127.0.0.1"))
    }

    @Test fun reportsConnectionErrors() {
        val last = run("true", server.copy(port = 1)).last()
        assertEquals(OutputTag.ERROR, last.first)
        assertTrue(last.second, last.second.startsWith("\nError: "))
    }

    @Test fun downloadsFolderRecursively() {
        File(sftpRoot, "builds/app/sub").mkdirs()
        File(sftpRoot, "builds/app/a.txt").writeText("aaa")
        File(sftpRoot, "builds/app/sub/b.bin").writeBytes(ByteArray(300_000) { it.toByte() })
        val (sink, written) = memorySink()
        val lines = mutableListOf<Pair<OutputTag, String>>()
        runner.download(server, "/builds/app", sink, { tag, text -> lines += tag to text })

        assertEquals(setOf("a.txt", "sub/b.bin"), written.keys)
        assertEquals("aaa", written["a.txt"]!!.toString(Charsets.UTF_8))
        assertEquals(300_000, written["sub/b.bin"]!!.size())
        assertTrue(lines.toString(), lines.any { it.second == "  sub/b.bin (293 KB)\n" })
        assertTrue(lines.last().second, lines.last().second.startsWith("\n✓ Downloaded 2 files (0.3 MB) to mem ("))
    }

    @Test fun downloadsSingleFileAndReportsMissing() {
        File(sftpRoot, "app.apk").writeText("apk")
        val (sink, written) = memorySink()
        runner.download(server, "/app.apk", sink, { _, _ -> })
        assertEquals(setOf(""), written.keys)

        val lines = mutableListOf<Pair<OutputTag, String>>()
        runner.download(server, "/missing", memorySink().first, { tag, text -> lines += tag to text })
        assertEquals(OutputTag.ERROR, lines.last().first)
    }

    @Test fun cancelStopsARunningCommand() {
        val cancellation = Cancellation()
        val lines = mutableListOf<Pair<OutputTag, String>>()
        val t = Thread { runner.runCommand(server, "echo started; sleep 30", { tag, text -> lines += tag to text }, cancellation) }
        t.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (lines.none { it.second == "started\n" } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        cancellation.cancel()
        t.join(5_000)
        assertTrue("runner should stop", !t.isAlive)
        assertTrue(lines.last().second, lines.last().second.startsWith("\nCancelled ("))
    }

    private fun memorySink(): Pair<DownloadSink, Map<String, java.io.ByteArrayOutputStream>> {
        val written = linkedMapOf<String, java.io.ByteArrayOutputStream>()
        return object : DownloadSink {
            override val description = "mem"
            override fun open(rel: List<String>): OutputStream =
                java.io.ByteArrayOutputStream().also { written[rel.joinToString("/")] = it }
        } to written
    }
}
