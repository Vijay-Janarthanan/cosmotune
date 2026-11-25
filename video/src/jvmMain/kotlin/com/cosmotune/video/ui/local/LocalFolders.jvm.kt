package com.cosmotune.video.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.local.LocalVideo
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Makes a video's picture on the desktop: the desktop app's own FFmpeg decoder, handed in by its Video host
 * (`installLocalThumbnails`), which gives back the encoded image ([path], at most [widthPx] wide). Null: no pictures, the tiles show
 * their icon.
 */
object LocalVideoThumbnails {
    @Volatile
    var grab: ((path: String, widthPx: Int) -> ByteArray?)? = null
}

/** The desktop's folders: plain paths, listed directly; the pictures kept as JPEG files beside the other video files. */
private object DesktopLocalFolderAccess : LocalFolderAccess {
    private val thumbs by lazy { File(videoLibraryDir(LibraryPlatform.Desktop), "local-thumbs").apply { mkdirs() } }

    override fun list(folder: String): List<FolderEntry> = runCatching {
        File(folder).listFiles().orEmpty()
            .filter { !it.isHidden && !it.name.startsWith(".") && (it.isDirectory || LocalVideo.isVideoName(it.name)) }
            .map { FolderEntry(it.absolutePath, it.name, it.isDirectory, it.lastModified()) }
    }.getOrDefault(emptyList())

    override fun thumbnail(video: String, widthPx: Int): ImageBitmap? {
        val file = File(video)
        val cached = File(thumbs, thumbFileName(video, file.lastModified()))
        val bytes = if (cached.isFile) runCatching { cached.readBytes() }.getOrNull() else null
        val made = bytes ?: LocalVideoThumbnails.grab?.invoke(video, widthPx)?.also { runCatching { cached.writeBytes(it) } }
        return made?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }
}

@Composable
actual fun rememberLocalFolderAccess(): LocalFolderAccess = DesktopLocalFolderAccess

@Composable
actual fun rememberLocalFolderStore(): LocalFolderStore = remember { LocalFolderStores.of(videoLibraryDir(LibraryPlatform.Desktop)) }

/** The desktop's folder picker: Swing's, in the system's own look, folders only. */
@Composable
actual fun rememberFolderPicker(onFolder: (PickedVideo) -> Unit): () -> Unit {
    val chosen = rememberUpdatedState(onFolder)
    return remember {
        {
            val before = UIManager.getLookAndFeel()
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Add a folder of videos"
                isAcceptAllFileFilterUsed = false
            }
            runCatching { UIManager.setLookAndFeel(before) }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val folder = chooser.selectedFile
                if (folder != null && folder.isDirectory) chosen.value(PickedVideo(folder.absolutePath, folder.name.ifBlank { folder.absolutePath }))
            }
        }
    }
}
