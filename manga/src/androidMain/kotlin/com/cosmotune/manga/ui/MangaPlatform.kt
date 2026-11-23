package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidColorFilter
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.data.platformMangaFileProtection
import java.io.File
import java.io.InputStream
import android.provider.DocumentsContract
import androidx.compose.runtime.rememberCoroutineScope
import com.cosmotune.manga.data.MangaFolderImport
import com.cosmotune.manga.data.MangaImageImport
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun ReaderKeepScreenOn(enabled: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable internal actual fun rememberReaderRotation(): ReaderRotation? {
    val activity = LocalContext.current.findActivity() ?: return null
    val locked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Leaving the reader gives the screen back to the system's rotation.
    androidx.compose.runtime.DisposableEffect(activity) { onDispose { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    return ReaderRotation(locked.value) {
        locked.value = !locked.value
        activity.requestedOrientation = if (locked.value) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable internal actual fun rememberMangaRepository(): Result<MangaRepository> {
    val context = LocalContext.current.applicationContext
    return remember(context) { runCatching {
        AndroidMangaLibrary.repository(context)
    } }
}
@Composable internal actual fun rememberMangaImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Comic.cbz"
            latest.value(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this document" } }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/x-tar", "application/gzip", "application/x-gzip", "application/octet-stream")) } }
}
@Composable internal actual fun MangaBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable internal actual fun rememberMangaFolderImport(onPicked: (Result<MangaFolderImport>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val resolver = context.contentResolver
                val documentId = DocumentsContract.getTreeDocumentId(tree)
                val images = ArrayList<MangaImageImport>()
                val folders = ArrayList<Pair<String, String>>()
                // A series folder holds its pages, or one subfolder per chapter (one level).
                fun scan(parentId: String, chapter: String) {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1)
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (chapter.isEmpty() && folders.size <= 1_000) folders += cursor.getString(0) to name
                            } else if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif")) {
                                require(images.size < 10_000) { "Folder contains too many images" }
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                                images += MangaImageImport(name, chapter) { requireNotNull(resolver.openInputStream(uri)) }
                            }
                        }
                    }
                }
                scan(documentId, "")
                if (images.isEmpty()) folders.forEach { (id, name) -> scan(id, name) }
                MangaFolderImport(documentId.substringAfterLast('/').substringAfterLast(':'), images)
            } }
            latest.value(result)
        }
    }
    return remember(launcher) { { launcher.launch(null) } }
}

@Composable internal actual fun mangaLockAvailable(): Boolean {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context) {
        (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isDeviceSecure == true
    }
}

@Composable internal actual fun rememberMangaLockPrompt(): String? = "your screen lock"
@Composable internal actual fun mangaHideDetail(): String? = "Also hides Manga in recent apps"

/** When Manga last passed the lock, for the five-minute grace; per process, so a restart asks again. */
private object MangaLockClock { @Volatile var unlockedAtMs = 0L }

@Suppress("DEPRECATION")
@Composable internal actual fun MangaLockGate(enabled: Boolean, pin: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val keyguard = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
    val needed = enabled && keyguard?.isDeviceSecure == true
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!needed || System.currentTimeMillis() - MangaLockClock.unlockedAtMs < 5 * 60_000L) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) { MangaLockClock.unlockedAtMs = System.currentTimeMillis(); open = true }
    }
    fun unlock() { keyguard?.createConfirmDeviceCredentialIntent("Manga", "Unlock to open Manga")?.let { runCatching { ask.launch(it) } } ?: run { open = true } }
    androidx.compose.runtime.LaunchedEffect(needed) { if (!needed) open = true else if (!open) unlock() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (open) MangaLockClock.unlockedAtMs = System.currentTimeMillis() } }
    if (open) content() else MangaLockedPage(::unlock)
}

@Composable internal actual fun MangaSecureScreen(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    androidx.compose.runtime.DisposableEffect(activity, enabled) {
        if (enabled) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

internal actual val mangaPlaysAnimation: Boolean get() = android.os.Build.VERSION.SDK_INT >= 28

/** The platform's ImageDecoder plays the page (API 28 and later), drawn through Compose so the reader's zoom and taps still apply. */
@Composable internal actual fun MangaAnimatedPage(file: java.io.File, fillWidth: Boolean, filter: androidx.compose.ui.graphics.ColorFilter?, modifier: androidx.compose.ui.Modifier) {
    if (android.os.Build.VERSION.SDK_INT < 28) return
    val drawable = androidx.compose.runtime.produceState<android.graphics.drawable.Drawable?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file)) }.getOrNull() }
    }.value ?: return
    var tick by androidx.compose.runtime.remember(drawable) { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(drawable) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        drawable.callback = object : android.graphics.drawable.Drawable.Callback {
            override fun invalidateDrawable(who: android.graphics.drawable.Drawable) { tick++ }
            override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, at: Long) { handler.postAtTime(what, at) }
            override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
        (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
        onDispose { (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop(); drawable.callback = null }
    }
    drawable.colorFilter = filter?.asAndroidColorFilter()
    val width = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
    val height = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
    androidx.compose.foundation.Canvas(if (fillWidth) modifier.aspectRatio(width / height) else modifier) {
        if (tick < 0) return@Canvas // read so each new frame draws again
        val scale = minOf(size.width / width, size.height / height)
        val left = (size.width - width * scale) / 2f
        val top = (size.height - height * scale) / 2f
        drawable.setBounds(left.toInt(), top.toInt(), (left + width * scale).toInt(), (top + height * scale).toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

@Composable internal actual fun rememberMangaFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

@Composable internal actual fun MangaOpdsControls(state: com.cosmotune.manga.data.MangaLibraryState, update: ((com.cosmotune.manga.data.MangaLibraryState) -> com.cosmotune.manga.data.MangaLibraryState) -> Unit) = Unit
