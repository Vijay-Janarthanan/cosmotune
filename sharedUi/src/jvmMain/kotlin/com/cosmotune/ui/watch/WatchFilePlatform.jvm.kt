package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

private fun pick(title: String, mode: Int, name: String? = null): File? {
    var selected: File? = null
    SwingUtilities.invokeAndWait {
        val picker = FileDialog(null as Frame?, title, mode)
        try {
            name?.let { picker.file = it }
            picker.isVisible = true
            picker.file?.let { selected = File(picker.directory, it) }
        } finally { picker.dispose() }
    }
    return selected
}

@Composable internal actual fun rememberTextFileSaver(): (name: String, text: String, onDone: (Boolean) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) { { name, text, done -> scope.launch {
        val saved = withContext(Dispatchers.IO) {
            val file = pick("Save Watch backup", FileDialog.SAVE, name) ?: return@withContext false
            runCatching { file.writeText(text) }.isSuccess
        }
        done(saved)
    }; Unit } }
}

@Composable internal actual fun rememberTextFileOpener(onPicked: (String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val text = withContext(Dispatchers.IO) {
            pick("Choose a Watch backup", FileDialog.LOAD)?.takeIf { it.isFile && it.length() <= 32 shl 20 }?.let { runCatching { it.readText() }.getOrNull() }
        }
        if (text != null) latest.value(text)
    }; Unit } }
}
