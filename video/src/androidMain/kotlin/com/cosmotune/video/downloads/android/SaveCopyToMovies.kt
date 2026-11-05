package com.cosmotune.video.downloads.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadKind
import com.cosmotune.video.downloads.localFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Save a copy to Movies" (spec 5.10, open decision Q11): copies a finished video into the shared `Movies/Cosmotune` folder so
 * other players find it. On Android 10 (API 29) and later through `MediaStore`, which needs no permission; nothing is ever
 * requested. Below API 29 it copies into the public Movies folder only when the app already holds `WRITE_EXTERNAL_STORAGE` (the
 * app declares it up to API 28), else it answers false. Subtitles are not copied (the media store refuses text files); an
 * audio-only download and a pair kept after a failed join are not one playable video, so they are refused too.
 */
class SaveCopyToMovies(private val context: Context) {
    /** Copies [entry]'s video; true when the copy is in Movies/Cosmotune. */
    suspend fun save(entry: DownloadEntry): Boolean = withContext(Dispatchers.IO) {
        val path = entry.localFiles()?.muxedPath
        if (path == null || entry.kind == DownloadKind.AUDIO) return@withContext false
        val source = File(path)
        if (!source.isFile) return@withContext false
        val mime = if (source.extension.equals("webm", ignoreCase = true)) "video/webm" else "video/mp4"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) throughMediaStore(source, mime) else intoPublicFolder(source)
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun throughMediaStore(source: File, mime: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: error("no stream for the copy")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    /** API 26 to 28: the public Movies folder, only with the storage permission already granted (never asked for here). */
    private fun intoPublicFolder(source: File): Boolean {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
        val target = File(folder, source.name)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        return true
    }

    private companion object {
        const val FOLDER = "Cosmotune"
    }
}
