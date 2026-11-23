package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

@Composable internal actual fun ReaderKeepScreenOn(enabled: Boolean) = Unit
@Composable internal actual fun rememberReaderRotation(): ReaderRotation? = null
@Composable internal actual fun rememberMangaRepository(): Result<MangaRepository> = remember { runCatching {
    DesktopMangaLibrary.repository
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
                    dialog.setFilenameFilter { _, name -> com.cosmotune.manga.data.MangaArchive.supports(name) }
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

// A computer locks Manga behind Windows Hello or Touch ID where it has them, otherwise behind Manga's own PIN.
@Composable internal actual fun mangaLockAvailable(): Boolean = true

@Composable internal actual fun rememberMangaLockPrompt(): String? =
    androidx.compose.runtime.produceState<String?>("") { value = withContext(Dispatchers.IO) { MangaDesktopLock.system.label.ifEmpty { null } } }.value

@Composable internal actual fun mangaHideDetail(): String? = "Also keeps the window out of screen sharing".takeIf { MangaDesktopLock.canHideFromCapture }

/** When Manga last passed the lock, for the five-minute grace; per process, so a restart asks again. */
private object DesktopLockClock { @Volatile var unlockedAtMs = 0L }

@Composable internal actual fun MangaLockGate(enabled: Boolean, pin: String?, content: @Composable () -> Unit) {
    val prompt = rememberMangaLockPrompt()
    // Locked only once it is known what can unlock it; with neither a prompt nor a PIN there is nothing to ask, so it stays open.
    val needed = enabled && prompt != "" && (prompt != null || pin != null)
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis() - DesktopLockClock.unlockedAtMs < 5 * 60_000L) }
    var asking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun pass() { DesktopLockClock.unlockedAtMs = System.currentTimeMillis(); open = true }
    fun ask() { if (!asking && prompt != null && prompt != "") { asking = true; scope.launch { if (MangaDesktopLock.ask()) pass(); asking = false } } }
    androidx.compose.runtime.LaunchedEffect(needed) { if (needed && !open) ask() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (open) DesktopLockClock.unlockedAtMs = System.currentTimeMillis() } }
    when {
        !enabled || prompt != "" && !needed || open -> content()
        prompt == "" -> Unit // still finding out what this computer has; a moment at most
        else -> MangaLockedPage(onUnlock = if (prompt != null) ::ask else null,
            onPin = if (pin != null) { typed -> com.cosmotune.manga.data.MangaLockPin.matches(typed, pin).also { if (it) pass() } } else null)
    }
}

/**
 * Compose's own record of the AWT window a composable is in (`androidx.compose.ui.window.LocalWindow`), which Compose keeps
 * internal; read by name, as the desktop's video does.
 */
@Suppress("UNCHECKED_CAST")
private val AwtWindowLocal: androidx.compose.runtime.CompositionLocal<java.awt.Window?>? = runCatching {
    Class.forName("androidx.compose.ui.window.LocalWindowKt").getMethod("getLocalWindow").invoke(null)
        as androidx.compose.runtime.CompositionLocal<java.awt.Window?>
}.getOrNull()

@Composable internal actual fun MangaSecureScreen(enabled: Boolean) {
    val window = AwtWindowLocal?.current
    androidx.compose.runtime.DisposableEffect(window, enabled) {
        if (enabled) MangaDesktopLock.hideFromCapture(window, true)
        onDispose { if (enabled) MangaDesktopLock.hideFromCapture(window, false) }
    }
}
