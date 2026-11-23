package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO

/** The desktop: mpv writes the frame into Pictures/Cosmotune, and the picture is put on the clipboard too. */
@Composable
internal actual fun rememberVideoScreenshots(playback: VideoPlayback): VideoScreenshots? = remember(playback) {
    object : VideoScreenshots {
        override suspend fun take(title: String, positionMs: Long): Boolean {
            val folder = File(System.getProperty("user.home"), "Pictures${File.separator}Cosmotune")
            val file = withContext(Dispatchers.IO) {
                folder.mkdirs()
                unique(File(folder, screenshotName(title, positionMs)))
            }
            if (!playback.engine.screenshot(file.absolutePath)) return false
            return withContext(Dispatchers.IO) {
                if (!file.isFile) return@withContext false
                runCatching { ImageIO.read(file)?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransfer(it), null) } }
                true
            }
        }
    }
}

private fun unique(file: File): File {
    if (!file.exists()) return file
    val base = file.nameWithoutExtension
    var n = 2
    while (true) {
        val next = File(file.parentFile, "$base ($n).png")
        if (!next.exists()) return next
        n++
    }
}

private class ImageTransfer(private val image: Image) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any = image
}
