package dev.sshdeck

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.sshdeck.core.DownloadSink
import dev.sshdeck.core.KnownHosts
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

class PrefsKnownHosts(context: Context) : KnownHosts {
    private val prefs = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)
    override fun get(hostId: String): String? = prefs.getString(hostId, null)
    override fun set(hostId: String, fingerprint: String) = prefs.edit().putString(hostId, fingerprint).apply()
    override fun remove(hostId: String) = prefs.edit().remove(hostId).apply()
}

/**
 * Saves `#@download` files into the phone's Downloads folder. Desktop `local:` paths like
 * `~/Downloads/android-build` don't exist here, so only their last component is kept:
 * a folder lands in Download/android-build/, a single file as Download/app.apk.
 */
class DownloadsSink(private val context: Context, local: String) : DownloadSink {
    private val baseName = local.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        .ifEmpty { "ssh-deck-download" }

    override val description = "Download/$baseName"

    override fun open(rel: List<String>): OutputStream {
        val parts = listOf(baseName) + rel
        val name = parts.last()
        val dirs = parts.dropLast(1)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before scoped storage, use the app's own Downloads dir (needs no permission).
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            return File(root, parts.joinToString("/")).apply { parentFile!!.mkdirs() }.outputStream()
        }
        val relativePath = (listOf(Environment.DIRECTORY_DOWNLOADS) + dirs).joinToString("/") + "/"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Overwrite a file this app saved earlier instead of creating "name (1)".
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, name), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) {
            val uri = android.content.ContentUris.withAppendedId(collection, existing)
            return resolver.openOutputStream(uri, "wt")!!
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(name))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create $relativePath$name")
        val stream = resolver.openOutputStream(uri, "w")!!
        // Publish the file only once it is complete.
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }

    private fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
