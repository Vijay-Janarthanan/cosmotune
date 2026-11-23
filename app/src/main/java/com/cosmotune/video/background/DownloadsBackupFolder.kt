package com.cosmotune.video.background

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cosmotune.video.alerts.BackupFolder
import com.cosmotune.video.alerts.FileBackupFolder
import java.io.File

/**
 * The phone's automatic backups folder: Downloads/Cosmotune backups, through the media store (Android 10 and later: no permission
 * needed for the app's own files); before Android 10 the app's own folder on the shared storage.
 */
class DownloadsBackupFolder(private val context: Context) : BackupFolder {
    private val legacy by lazy { FileBackupFolder(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER)) }

    override fun list(): List<String> {
        if (Build.VERSION.SDK_INT < 29) return legacy.list()
        val names = ArrayList<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        runCatching {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(RELATIVE), null)?.use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
        }
        return names
    }

    override fun write(name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < 29) return legacy.write(name, bytes)
        val resolver = context.contentResolver
        delete(name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create $name")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write $name")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun delete(name: String) {
        if (Build.VERSION.SDK_INT < 29) return legacy.delete(name)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        runCatching { context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, arrayOf(RELATIVE, name)) }
    }

    private companion object {
        const val FOLDER = "Cosmotune backups"
        val RELATIVE = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
    }
}
