package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.data.platformMangaFileProtection
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import com.cosmotune.manga.data.MangaFolderImport
import com.cosmotune.manga.data.MangaImageImport

@Composable internal actual fun rememberMangaRepository(): Result<MangaRepository> = remember { runCatching {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val root = when {
        "win" in os -> File(System.getenv("APPDATA") ?: home, "Cosmotune/manga")
        "mac" in os -> File(home, "Library/Application Support/Cosmotune/manga")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "Cosmotune/manga")
    }
    MangaRepository(MangaStore(root, platformMangaFileProtection()))
} }
@Composable internal actual fun rememberMangaImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val file = withContext(Dispatchers.IO) {
            var selected: File? = null
            SwingUtilities.invokeAndWait {
                val dialog = FileDialog(null as Frame?, "Import comic archive", FileDialog.LOAD)
                try {
                    dialog.setFilenameFilter { _, name -> name.endsWith(".cbz", true) || name.endsWith(".zip", true) }
                    dialog.isVisible = true
                    dialog.file?.let { selected = File(dialog.directory, it) }
                } finally { dialog.dispose() }
            }
            selected
        }
        if (file != null) latest.value(file.name) { file.inputStream() }
    }; Unit } }
}
@Composable internal actual fun MangaBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable internal actual fun rememberMangaFolderImport(onPicked: (Result<MangaFolderImport>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            var directory: File? = null
            SwingUtilities.invokeAndWait {
                val picker = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Import manga image folder" }
                if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) directory = picker.selectedFile
            }
            directory?.let { folder ->
                fun images(directory: File) = directory.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") }
                // A series folder holds its pages, or one subfolder per chapter (one level, symbolic links are not followed).
                val direct = images(folder)
                val chapters = if (direct.isNotEmpty()) emptyList() else folder.listFiles().orEmpty()
                    .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.take(1_001)
                val imports = direct.map { image -> MangaImageImport(image.name) { image.inputStream() } } +
                    chapters.flatMap { chapter -> images(chapter).map { image -> MangaImageImport(image.name, chapter.name) { image.inputStream() } } }
                require(imports.size <= 10_000) { "Folder contains too many images" }
                MangaFolderImport(folder.name, imports)
            }
        } }
        result.fold(onSuccess = { if (it != null) latest.value(Result.success(it)) }, onFailure = { latest.value(Result.failure(it)) })
    }; Unit } }
}
