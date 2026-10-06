package dev.sshdeck.core

/**
 * A script whose first line is `#@download` is not run remotely; instead it copies
 * a remote file or folder to this device over SFTP:
 *
 *     #@download
 *     remote: C:\path\on\server\SomeFolder
 *     local: ~/Downloads/some-folder
 */
data class DownloadDirective(val remote: String, val local: String) {
    companion object {
        const val MARKER = "#@download"

        /** Returns null when [content] is an ordinary script. */
        fun parse(content: String): DownloadDirective? {
            val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            // Skip the PROJECT= line injected for _shared scripts.
            val project = if (lines.firstOrNull()?.startsWith("PROJECT=") == true) {
                runCatching { Shlex.split(lines.removeAt(0).substringAfter('=')).joinToString("") }
                    .getOrElse { return null }
            } else ""
            if (lines.firstOrNull() != MARKER) return null

            val fields = mutableMapOf<String, String>()
            for (ln in lines.drop(1)) {
                if (ln.startsWith("#") || ':' !in ln) continue
                val key = ln.substringBefore(':').trim().lowercase(java.util.Locale.ROOT)
                fields[key] = ln.substringAfter(':').trim().replace("\$PROJECT", project)
            }
            return DownloadDirective(
                remote = fields["remote"] ?: return null,
                local = fields["local"] ?: return null,
            )
        }
    }
}
