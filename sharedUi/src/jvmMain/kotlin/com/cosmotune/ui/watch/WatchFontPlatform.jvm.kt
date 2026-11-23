package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal actual fun subtitleFontFamily(path: String): FontFamily? =
    runCatching { File(path).takeIf { it.isFile }?.let { FontFamily(Font(it)) } }.getOrNull()

@Composable internal actual fun rememberFontFilePicker(onPicked: (String, ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val file = withContext(Dispatchers.IO) {
            var selected: File? = null
            SwingUtilities.invokeAndWait {
                val picker = FileDialog(null as Frame?, "Choose a font for subtitles", FileDialog.LOAD)
                try {
                    picker.setFilenameFilter { _, name -> name.endsWith(".ttf", true) || name.endsWith(".otf", true) || name.endsWith(".ttc", true) }
                    picker.isVisible = true
                    picker.file?.let { selected = File(picker.directory, it) }
                } finally { picker.dispose() }
            }
            selected?.takeIf { it.isFile && it.length() <= 20 shl 20 }
        }
        if (file != null) latest.value(file.name, withContext(Dispatchers.IO) { file.readBytes() })
    }; Unit } }
}
