package dev.sshdeck.core

import java.util.Locale

// Ports of the small string helpers in the desktop app's ssh_deck.py. They are
// kept behaviour-identical so scripts and configs work the same on both.

private val ANSI_RE = Regex("\u001b[\\[(][0-9;?]*[a-zA-Z]|\u001b[=>]|\u001b][^\u0007]*\u0007|\r")
private val BLANK_LINES_RE = Regex("\n{3,}")

fun stripAnsi(text: String): String =
    BLANK_LINES_RE.replace(ANSI_RE.replace(text, ""), "\n\n")

private val NON_SLUG_RE = Regex("[^a-z0-9]+")

/** `Git Pull` -> `git-pull.sh`. */
fun labelToScriptName(label: String): String {
    val name = label.lowercase(Locale.ROOT).trim()
    return NON_SLUG_RE.replace(name, "-").trim('-') + ".sh"
}

private val WINDOWS_PATH_RE = Regex("^[A-Za-z]:[\\\\/]")

/** Windows paths (C:\foo\bar) become /C:/foo/bar for the OpenSSH SFTP server. */
fun toSftpPath(path: String): String =
    if (WINDOWS_PATH_RE.containsMatchIn(path)) "/" + path.replace('\\', '/') else path

fun formatElapsed(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m${secs.toString().padStart(2, '0')}s" else "${secs}s"
}

/** Wrap a command in a login shell so the user's profile (PATH etc.) loads. */
fun buildExecCommand(command: String, loginShell: Boolean): String =
    if (loginShell) "bash -l -c '${command.replace("'", "'\\''")}'" else command

object Shlex {
    private val SAFE_RE = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    /** Python's shlex.quote. */
    fun quote(s: String): String = when {
        s.isEmpty() -> "''"
        SAFE_RE.matches(s) -> s
        else -> "'" + s.replace("'", "'\"'\"'") + "'"
    }

    /** Python's shlex.split in POSIX mode (no comments); throws on unclosed quotes. */
    fun split(s: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inToken = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c in " \t\r\n" -> {
                    if (inToken) tokens += cur.toString()
                    cur.clear()
                    inToken = false
                }
                c == '\'' -> {
                    val end = s.indexOf('\'', i + 1)
                    require(end >= 0) { "No closing quotation" }
                    cur.append(s, i + 1, end)
                    inToken = true
                    i = end
                }
                c == '"' -> {
                    i++
                    while (true) {
                        require(i < s.length) { "No closing quotation" }
                        val d = s[i]
                        if (d == '"') break
                        if (d == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) i++
                        cur.append(s[i])
                        i++
                    }
                    inToken = true
                }
                c == '\\' -> {
                    require(i + 1 < s.length) { "No escaped character" }
                    i++
                    cur.append(s[i])
                    inToken = true
                }
                else -> {
                    cur.append(c)
                    inToken = true
                }
            }
            i++
        }
        if (inToken) tokens += cur.toString()
        return tokens
    }
}
