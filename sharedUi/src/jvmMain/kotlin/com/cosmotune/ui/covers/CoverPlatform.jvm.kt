package com.cosmotune.ui.covers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.SwingUtilities

private val PHOTO_TYPES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** The desktop's file chooser, for pictures only. */
@Composable internal actual fun rememberCoverPhotoChooser(onChosen: (Any) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onChosen)
    return remember(scope) {
        {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    var selected: File? = null
                    SwingUtilities.invokeAndWait {
                        val picker = FileDialog(null as Frame?, "Choose a photo", FileDialog.LOAD)
                        try {
                            picker.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in PHOTO_TYPES }
                            // Windows ignores the filter above; this pattern is what its own dialog lists.
                            picker.file = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp"
                            picker.isVisible = true
                            picker.file?.takeIf { picker.directory != null }?.let { selected = File(picker.directory, it) }
                        } finally {
                            picker.dispose()
                        }
                    }
                    selected?.takeIf { it.isFile }
                }
                if (file != null) latest.value(file.toURI().toString())
            }
            Unit
        }
    }
}

internal actual fun encodeCoverJpeg(argb: IntArray, side: Int, quality: Int): ByteArray {
    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, side, side, argb, 0, side)
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    return try {
        ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality.coerceIn(0, 100) / 100f
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            out.toByteArray()
        }
    } finally {
        writer.dispose()
    }
}

internal actual val coverPlaceHint: String = "Drag to move the photo and use the mouse wheel to zoom."
