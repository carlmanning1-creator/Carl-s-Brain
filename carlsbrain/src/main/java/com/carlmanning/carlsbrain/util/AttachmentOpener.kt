package com.carlmanning.carlsbrain.util

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens a `file:<name>:<id>` attachment in whatever app handles its type.
 *
 * A non-image attachment rendered as an icon with no way to open it is a file Carl can see he
 * has and cannot read — which is barely better than not having attached it. Shared by the note
 * and to-do editors so the two cannot drift on how an entry is decoded.
 */
object AttachmentOpener {

    /** The display name inside a `file:<name>:<id>` entry, or null if this is a bare photo id. */
    fun fileName(entry: String): String? =
        if (entry.startsWith("file:")) entry.removePrefix("file:").substringBeforeLast(":")
        else null

    /** The Drive id, which is always the last colon-separated field. */
    fun driveId(entry: String): String =
        if (entry.startsWith("file:")) entry.substringAfterLast(":") else entry

    /**
     * Downloads (or reuses a cached copy of) the attachment and hands it to the system chooser.
     *
     * @return null on success, or a short message to show Carl. Every failure is a message
     *   rather than an exception: this is reached from a tap on a tile, where a crash would be
     *   the only feedback he got.
     */
    suspend fun open(context: Context, entry: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = fileName(entry) ?: return@runCatching "That attachment is an image"
            val id = driveId(entry)

            // Cached under the real filename, so the extension survives — the whole point.
            // A cache subdirectory of its own, declared in file_provider_paths.xml.
            val dir = File(context.cacheDir, "attachment_files").also { it.mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
            val target = File(dir, "${id}_$safe")

            if (!target.exists() || target.length() == 0L) {
                val bytes = DriveRepository(context).downloadPhotoBytes(id)
                    ?: return@runCatching "Couldn't download that file"
                target.writeBytes(bytes)
            }

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", target
            )
            val extension = safe.substringAfterLast('.', "")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: "application/octet-stream"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open $name").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            null
        }.getOrElse {
            ErrorLog.record("AttachmentOpener", it)
            "Couldn't open that file"
        }
    }
}
