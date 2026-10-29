package com.cosmotune.video.ui.local

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.local.LocalRecentStore
import com.cosmotune.video.local.LocalSubtitles
import com.cosmotune.video.local.LocalVideo
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.DataFlavor
import java.io.File

/** The desktop's files: plain paths, a folder listing for the subtitles beside a video. */
private object DesktopLocalFileAccess : LocalFileAccess {
    override fun exists(source: String): Boolean = runCatching { File(source).isFile }.getOrDefault(false)

    override fun siblingSubtitles(source: String): List<LocalSubtitleFile> {
        val video = File(source)
        val folder = video.parentFile ?: return emptyList()
        val names = runCatching { folder.list()?.toList() }.getOrNull().orEmpty()
        return LocalSubtitles.siblings(video.name, names).map { LocalSubtitleFile(File(folder, it.fileName).path, it) }
    }

    override fun keepAccess(source: String) = Unit
}

@Composable
actual fun rememberLocalFileAccess(): LocalFileAccess = DesktopLocalFileAccess

@Composable
actual fun rememberLocalRecentStore(): LocalRecentStore = remember { LocalRecentStores.of(videoLibraryDir(LibraryPlatform.Desktop)) }

/** The desktop's pickers: the system's own dialog through `java.awt.FileDialog`, modal on the window that has the focus. */
private class AwtVideoFilePicker(
    private val videos: State<(List<PickedVideo>) -> Unit>,
    private val subtitle: State<(PickedVideo) -> Unit>,
) : VideoFilePicker {
    override fun openVideos() {
        val dialog = dialog()
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> LocalVideo.isVideoName(name) }
        // Windows' dialog ignores the filter above and reads a pattern in the name field instead
        if (IS_WINDOWS) dialog.file = LocalVideo.VIDEO_EXTENSIONS.sorted().joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val picked = dialog.files.orEmpty().filter { it.isFile }.map { PickedVideo(it.absolutePath, it.name) }
        if (picked.isNotEmpty()) videos.value(picked)
    }

    override fun openSubtitle() {
        val dialog = dialog()
        dialog.setFilenameFilter { _, name -> LocalVideo.isSubtitleName(name) }
        if (IS_WINDOWS) dialog.file = LocalVideo.SUBTITLE_EXTENSIONS.sorted().joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = File(dialog.directory.orEmpty(), name)
        if (file.isFile) subtitle.value(PickedVideo(file.absolutePath, file.name))
    }

    private fun dialog(): FileDialog = when (val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) {
        is Frame -> FileDialog(owner, "", FileDialog.LOAD)
        is Dialog -> FileDialog(owner, "", FileDialog.LOAD)
        else -> FileDialog(null as Frame?, "", FileDialog.LOAD)
    }

    private companion object {
        val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

@Composable
actual fun rememberVideoFilePicker(onVideos: (List<PickedVideo>) -> Unit, onSubtitle: (PickedVideo) -> Unit): VideoFilePicker {
    val videos = rememberUpdatedState(onVideos)
    val subtitle = rememberUpdatedState(onSubtitle)
    return remember { AwtVideoFilePicker(videos, subtitle) }
}

/** Video files dropped on the window (from the file manager) are handed to [onFiles]; anything that is not a video file is left out. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.videoDropTarget(onFiles: (List<PickedVideo>) -> Unit): Modifier {
    val latest = rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = droppedFiles(event).filter { LocalVideo.isVideoName(it.name) }
                if (files.isEmpty()) return false
                latest.value(files.map { PickedVideo(it.absolutePath, it.name) })
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { event -> droppedFiles(event).any { LocalVideo.isVideoName(it.name) } }, target = target)
}

/** The files a drag carries, or none when it carries something else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        emptyList()
    } else {
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>().filter { it.isFile }
    }
}.getOrDefault(emptyList())
