package com.cosmotune.video.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.local.LocalRecentStore
import com.cosmotune.video.local.SubtitleMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/*
 * What the local player (Video mode's player for files on the device) asks of the platform: the document pickers, the drop of files on
 * the desktop window, whether a file is still there, the subtitle files beside a video, and the recent list's one store.
 */

/** A file the person chose or dropped: where it is ([source]: a path, or a `content://` address on the phone) and its [name] as the platform shows it. */
class PickedVideo(val source: String, val name: String)

/** A subtitle file found beside a video: its [path] (or address) and what its name says of it. */
class LocalSubtitleFile(val path: String, val match: SubtitleMatch)

/** The device's files, as the local player needs them. */
interface LocalFileAccess {
    /** Whether the file at [source] can still be opened. */
    fun exists(source: String): Boolean

    /** The subtitle files that sit beside the video at [source] and carry its name; empty where the platform cannot list a folder. */
    fun siblingSubtitles(source: String): List<LocalSubtitleFile>

    /** Asks the platform to keep the right to read [source] after this session (the phone's document grants); does nothing elsewhere. */
    fun keepAccess(source: String)
}

/** The device's files for the screen being drawn. */
@Composable
expect fun rememberLocalFileAccess(): LocalFileAccess

/** The one store of the files the local player has opened, in the video folder (made once per process). */
@Composable
expect fun rememberLocalRecentStore(): LocalRecentStore

/** The platform's pickers: videos (several at once) and one subtitle file. Cancelling calls nothing. */
interface VideoFilePicker {
    /** Shows the picker for one or more video files. */
    fun openVideos()

    /** Shows the picker for one subtitle file (`.srt`, `.vtt`, `.ass`, `.ssa`). */
    fun openSubtitle()
}

/** The picker of the screen being drawn; [onVideos] and [onSubtitle] are called on the main thread each time a picker returns. */
@Composable
expect fun rememberVideoFilePicker(onVideos: (List<PickedVideo>) -> Unit, onSubtitle: (PickedVideo) -> Unit): VideoFilePicker

/** Makes the content take video files dropped on it (the desktop's window); on the phone it changes nothing. */
@Composable
expect fun Modifier.videoDropTarget(onFiles: (List<PickedVideo>) -> Unit): Modifier

/** The process-wide store: the desktop and the phone each give the video folder, once. */
internal object LocalRecentStores {
    private var store: LocalRecentStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun of(folder: File): LocalRecentStore = store ?: LocalRecentStore(FileLibraryFiles(folder), scope).also { made ->
        store = made
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { made.flush() } }, "cosmotune-local-videos-flush"))
    }
}
