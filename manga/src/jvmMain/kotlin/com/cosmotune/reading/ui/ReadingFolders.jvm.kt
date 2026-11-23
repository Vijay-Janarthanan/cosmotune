package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.cosmotune.reading.data.ReadingFolder
import com.cosmotune.reading.data.ReadingFolderFile
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun rememberReadingFolders(): ReadingFolderAccess {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : ReadingFolderAccess {
            override fun pick(onPicked: (ReadingFolder) -> Unit) {
                scope.launch {
                    val chosen = withContext(Dispatchers.IO) {
                        var selected: File? = null
                        SwingUtilities.invokeAndWait {
                            val picker = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Add a folder of books" }
                            if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selected = picker.selectedFile
                        }
                        selected
                    }
                    chosen?.let { onPicked(ReadingFolder(it.absolutePath, it.name.ifBlank { it.absolutePath })) }
                }
            }
            override suspend fun list(folder: ReadingFolder): List<ReadingFolderFile> = withContext(Dispatchers.IO) {
                val root = File(folder.location)
                check(root.isDirectory) { "The folder “${folder.name}” is not there any more. Add it again." }
                root.walkTopDown().maxDepth(9).filter { it.isFile && !it.isHidden }.take(5_000).map { file ->
                    ReadingFolderFile(file.absolutePath, file.name, file.length(), file.lastModified()) { file.inputStream() }
                }.toList()
            }
        }
    }
}
