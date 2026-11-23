package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun rememberTorrentFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val bytes = withContext(Dispatchers.IO) {
            var selected: File? = null
            SwingUtilities.invokeAndWait {
                val picker = FileDialog(null as Frame?, "Open a .torrent file", FileDialog.LOAD)
                try {
                    picker.setFilenameFilter { _, name -> name.endsWith(".torrent", true) }
                    picker.isVisible = true
                    picker.file?.let { selected = File(picker.directory, it) }
                } finally { picker.dispose() }
            }
            selected?.takeIf { it.isFile && it.length() <= 4 shl 20 }?.readBytes()
        }
        if (bytes != null) latest.value(bytes)
    }; Unit } }
}
