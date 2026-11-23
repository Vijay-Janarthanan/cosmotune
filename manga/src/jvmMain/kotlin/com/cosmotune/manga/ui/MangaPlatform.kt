package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.testTag
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

@Composable internal actual fun MangaLockGate(
    enabled: Boolean,
    pin: String?,
    /** Changes the library (a forgotten PIN's reset). */
    update: ((com.cosmotune.manga.data.MangaLibraryState) -> com.cosmotune.manga.data.MangaLibraryState) -> Unit,
    content: @Composable () -> Unit,
) {
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
        else -> {
            val window = AwtWindowLocal?.current
            val recovery = androidx.compose.runtime.produceState<String?>("") { value = withContext(Dispatchers.IO) { MangaDesktopLock.recovery } }.value // "" while finding out
            MangaLockedPage(onUnlock = if (prompt != null) ::ask else null,
                onPin = if (pin != null) { typed -> com.cosmotune.manga.data.MangaLockPin.matches(typed, pin).also { if (it) pass() } } else null,
                forgot = if (recovery == "") null else MangaForgotPin(com.cosmotune.manga.data.MangaPinRecovery.plan(recovery),
                    signIn = { MangaDesktopLock.askRecovery(window) }, update = update, onDone = ::pass))
        }
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

internal actual val mangaPlaysAnimation: Boolean = true

/** Skia's codec decodes every frame once (up to 240 frames and 256 MB), then the frames turn at their own pace. */
@Composable internal actual fun MangaAnimatedPage(file: File, fillWidth: Boolean, filter: androidx.compose.ui.graphics.ColorFilter?, modifier: androidx.compose.ui.Modifier) {
    val frames = androidx.compose.runtime.produceState<List<Pair<androidx.compose.ui.graphics.ImageBitmap, Int>>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { desktopFrames(file) }.getOrNull() }
    }.value?.takeIf { it.isNotEmpty() } ?: return
    var index by androidx.compose.runtime.remember(frames) { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(frames) {
        if (frames.size > 1) while (true) { kotlinx.coroutines.delay(frames[index].second.coerceAtLeast(20).toLong()); index = (index + 1) % frames.size }
    }
    val image = frames[index].first
    val width = image.width.toFloat()
    val height = image.height.toFloat()
    androidx.compose.foundation.Canvas(if (fillWidth) modifier.aspectRatio(width / height) else modifier) {
        val scale = minOf(size.width / width, size.height / height)
        val shown = androidx.compose.ui.unit.IntSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        drawImage(image, dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - shown.width) / 2f).toInt(), ((size.height - shown.height) / 2f).toInt()),
            dstSize = shown, colorFilter = filter)
    }
}

private fun desktopFrames(file: File): List<Pair<androidx.compose.ui.graphics.ImageBitmap, Int>> {
    val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(file.readBytes()))
    val info = org.jetbrains.skia.ImageInfo.makeN32Premul(codec.width, codec.height)
    val frameBytes = codec.width.toLong() * codec.height * 4
    val count = minOf(codec.frameCount, 240, (256L * 1024 * 1024 / frameBytes.coerceAtLeast(1)).toInt()).coerceAtLeast(1)
    val details = codec.framesInfo
    val bitmaps = ArrayList<org.jetbrains.skia.Bitmap>()
    for (frame in 0 until count) {
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(info) }
        val required = details.getOrNull(frame)?.requiredFrame ?: -1
        if (required in bitmaps.indices) {
            bitmap.installPixels(info, bitmaps[required].readPixels(info, info.minRowBytes, 0, 0), info.minRowBytes)
            codec.readPixels(bitmap, frame, required)
        } else codec.readPixels(bitmap, frame)
        bitmap.setImmutable()
        bitmaps += bitmap
    }
    return bitmaps.mapIndexed { frame, bitmap -> bitmap.asComposeImageBitmap() to (details.getOrNull(frame)?.duration ?: 100) }
}

@Composable internal actual fun rememberMangaFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val chosen = withContext(Dispatchers.IO) {
            var picked: String? = null
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Folder for CBZ archives" }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile?.path
            }
            picked
        }
        chosen?.let { latest.value(it) }
    }; Unit } }
}

@Composable internal actual fun MangaOpdsControls(state: com.cosmotune.manga.data.MangaLibraryState, update: ((com.cosmotune.manga.data.MangaLibraryState) -> com.cosmotune.manga.data.MangaLibraryState) -> Unit) {
    val key = state.opdsKey
    com.cosmotune.ui.orbit.OrbitSwitchRow(mangaText("Share with other readers (OPDS)"), state.opdsServer, { on ->
        update { it.copy(opdsServer = on, opdsKey = it.opdsKey ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)) }
    }, plain = true, detail = if (state.opdsServer && key != null) mangaText("Downloaded chapters, on your home network at") + " " + MangaOpdsServer.address(key)
        else mangaText("Lets KOReader, Panels and other OPDS readers on your network open your downloaded chapters"),
        modifier = androidx.compose.ui.Modifier.testTag("manga:opds"))
}
