@file:JvmName("DesktopMangaLibraryTools")
package com.cosmotune.manga.ui

import androidx.compose.runtime.*
import com.cosmotune.manga.data.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.*

object DesktopMangaLibrary {
    val repository: MangaRepository by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val root = when {
            "win" in os -> File(System.getenv("APPDATA") ?: home, "Cosmotune/manga")
            "mac" in os -> File(home, "Library/Application Support/Cosmotune/manga")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "Cosmotune/manga")
        }
        MangaRepository(MangaStore(root, platformMangaFileProtection()))
    }
    @Volatile private var scheduler: Job? = null
    @Synchronized fun startUpdates() {
        if (scheduler != null) return
        scheduler = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                try { repository.scheduledUpdate() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A failed source is retried on its next scheduled interval. */ }
                delay(60_000)
            }
        }
    }
    @Synchronized fun stopUpdates() { scheduler?.cancel(); scheduler = null }
}

@Composable internal actual fun MangaBackgroundUpdates(repository: MangaRepository) = Unit

@Composable internal actual fun rememberMangaBackupTransfer(onImport: (ByteArray) -> Unit, onResult: (Result<Unit>) -> Unit): MangaBackupTransfer {
    val scope = rememberCoroutineScope()
    val imported = rememberUpdatedState(onImport); val result = rememberUpdatedState(onResult)
    suspend fun choose(save: Boolean): File? = withContext(Dispatchers.IO) {
        var selected: File? = null
        SwingUtilities.invokeAndWait {
            val dialog = FileDialog(null as Frame?, if (save) "Export Manga library" else "Restore Manga library", if (save) FileDialog.SAVE else FileDialog.LOAD)
            try { dialog.file = "cosmotune-manga-library.json"; dialog.isVisible = true; dialog.file?.let { selected = File(dialog.directory, it) } }
            finally { dialog.dispose() }
        }
        selected
    }
    return MangaBackupTransfer({ bytes -> scope.launch {
        choose(true)?.let { file -> result.value(withContext(Dispatchers.IO) { runCatching { file.outputStream().use { it.write(bytes) } } }) }
    } }, { scope.launch { choose(false)?.let { file ->
        val contents = withContext(Dispatchers.IO) { runCatching { file.inputStream().use { it.readNBytes(MangaLibraryBackup.MAX_BYTES + 1) }.also { require(it.size <= MangaLibraryBackup.MAX_BYTES) { "Library backup exceeds 8 MB" } } } }
        contents.fold(onSuccess = { imported.value(it) }, onFailure = { result.value(Result.failure(it)) })
    } } })
}
