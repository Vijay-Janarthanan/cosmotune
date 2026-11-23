package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

@Composable internal actual fun rememberMangaShare(onResult: (String) -> Unit): MangaShare {
    val result = rememberUpdatedState(onResult)
    return remember {
        MangaShare(
            sharePage = { file ->
                Thread {
                    val image = runCatching { ImageIO.read(file) }.getOrNull()
                    if (image == null) result.value("This page could not be copied")
                    else runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
                            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
                            override fun getTransferData(flavor: DataFlavor): Image = image
                        }, null)
                        result.value("Page copied")
                    }.onFailure { result.value("This page could not be copied") }
                }.start()
            },
            savePage = { file, name ->
                SwingUtilities.invokeLater {
                    val dialog = FileDialog(null as Frame?, "Save page", FileDialog.SAVE)
                    try {
                        dialog.file = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(80).ifBlank { "Manga page" } + ".jpg"
                        dialog.isVisible = true
                        dialog.file?.let { chosen ->
                            runCatching { file.copyTo(File(dialog.directory, chosen), overwrite = true) }
                                .onSuccess { result.value("Page saved") }.onFailure { result.value("This page could not be saved") }
                        }
                    } finally { dialog.dispose() }
                }
            },
            shareLink = { _, url ->
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null); result.value("Link copied") }
                    .onFailure { result.value("The link could not be copied") }
            },
            shareLabel = "Copy page",
        )
    }
}
