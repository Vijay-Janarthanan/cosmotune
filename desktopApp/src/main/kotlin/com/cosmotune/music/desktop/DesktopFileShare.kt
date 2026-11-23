package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * Share the file itself on the desktop (the idea from Kreate, see NOTICE.md): a local song's file goes on the clipboard as a file,
 * so pasting it into a chat app, a mail or a folder sends or copies the song itself.
 */
internal object DesktopFileShare {
    fun file(song: Song): File? = song.localPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
        ?: song.localUri?.let { runCatching { File(java.net.URI(it)) }.getOrNull() }?.takeIf { it.isFile && it.canRead() }

    fun copy(song: Song): Boolean {
        val file = file(song) ?: return false
        val files = listOf(file)
        val transfer = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in transferDataFlavors
            override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.javaFileListFlavor) files else file.absolutePath
        }
        return runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(transfer, null) }.isSuccess
    }
}
