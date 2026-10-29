package com.cosmotune.video.ui.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.local.LocalRecentStore
import com.cosmotune.video.local.LocalSubtitles
import com.cosmotune.video.local.LocalVideo
import java.io.File

/**
 * The phone's files. A document the person picked is a `content://` address: it is opened through the content resolver (the player's
 * data sources read `content:` as they read `file:`), its right to be read again is kept with `takePersistableUriPermission` when the
 * provider allows it, and no folder can be listed, so a video there has no subtitles found beside it (the person picks one). A plain
 * path (a file the system hands over) is listed like the desktop's.
 */
private class AndroidLocalFileAccess(private val context: Context) : LocalFileAccess {
    override fun exists(source: String): Boolean = runCatching {
        if (source.startsWith("content:")) {
            context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use { true } ?: false
        } else {
            File(pathOf(source)).isFile
        }
    }.getOrDefault(false)

    override fun siblingSubtitles(source: String): List<LocalSubtitleFile> {
        if (source.startsWith("content:")) return emptyList()
        val video = File(pathOf(source))
        val folder = video.parentFile ?: return emptyList()
        val names = runCatching { folder.list()?.toList() }.getOrNull().orEmpty()
        return LocalSubtitles.siblings(video.name, names).map { LocalSubtitleFile(File(folder, it.fileName).path, it) }
    }

    override fun keepAccess(source: String) {
        if (!source.startsWith("content:")) return
        try {
            context.contentResolver.takePersistableUriPermission(Uri.parse(source), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // A provider that does not offer a lasting grant (a share from another app): the file plays now and may not later.
        }
    }

    private fun pathOf(source: String): String = if (source.startsWith("file:")) Uri.parse(source).path ?: source else source
}

@Composable
actual fun rememberLocalFileAccess(): LocalFileAccess {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidLocalFileAccess(context) }
}

@Composable
actual fun rememberLocalRecentStore(): LocalRecentStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { LocalRecentStores.of(videoLibraryDir(LibraryPlatform.Android(context.filesDir))) }
}

/** The phone's pickers: `OpenMultipleDocuments` for videos and `OpenDocument` for a subtitle file (copied into the cache, so its extension survives). */
private class AndroidVideoFilePicker(
    private val openVideos: () -> Unit,
    private val openSubtitle: () -> Unit,
) : VideoFilePicker {
    override fun openVideos() = openVideos.invoke()

    override fun openSubtitle() = openSubtitle.invoke()
}

@Composable
actual fun rememberVideoFilePicker(onVideos: (List<PickedVideo>) -> Unit, onSubtitle: (PickedVideo) -> Unit): VideoFilePicker {
    val context = LocalContext.current.applicationContext
    val videos by rememberUpdatedState(onVideos)
    val subtitle by rememberUpdatedState(onSubtitle)
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.map { PickedVideo(it.toString(), displayName(context, it)) }
        if (picked.isNotEmpty()) videos(picked)
    }
    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri)
            val copy = copyToCache(context, uri, name)
            if (copy != null) subtitle(PickedVideo(copy.path, name))
        }
    }
    return remember(videoLauncher, subtitleLauncher) {
        AndroidVideoFilePicker(
            openVideos = { runCatching { videoLauncher.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream")) } },
            openSubtitle = { runCatching { subtitleLauncher.launch(arrayOf("*/*")) } },
        )
    }
}

/** The phone has no window to drop files on. */
@Composable
actual fun Modifier.videoDropTarget(onFiles: (List<PickedVideo>) -> Unit): Modifier = this

/** The document's name as the provider shows it, else the last part of its address. */
private fun displayName(context: Context, uri: Uri): String {
    var name: String? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) name = cursor.getString(column)
        }
    } catch (e: Exception) {
        // a provider that cannot describe the document: fall back to its address
    }
    return name ?: LocalVideo.fileName(uri.toString())
}

/** A small file copied from [uri] to the cache folder under its own [name] (so a subtitle keeps its extension); null when it cannot be read. */
private fun copyToCache(context: Context, uri: Uri, name: String): File? = try {
    // One folder for each document, so the file keeps its own name (its label and language come from it) and two never collide.
    val folder = File(File(context.cacheDir, SUBTITLE_FOLDER), LocalVideo.idFor(uri.toString()).removePrefix(LocalVideo.ID_PREFIX)).apply { mkdirs() }
    val target = File(folder, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "subtitles.srt" })
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
    target.takeIf { it.isFile }
} catch (e: Exception) {
    null
}

private const val SUBTITLE_FOLDER = "local-subtitles"
