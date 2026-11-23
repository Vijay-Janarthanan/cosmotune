package com.cosmotune.reading.ui

import com.cosmotune.manga.ui.ReadTextButton
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cosmotune.reading.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*

internal actual val readingCenterTap: Boolean = true

@Composable actual fun ReadingDocumentView(document: ReadingDocument, locator: ReadingLocator,
    preferences: ReadingPreferences, searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit,
    onError: (String) -> Unit, modifier: Modifier, onCenterTap: () -> Unit, control: ReadingPageControl?) {
    when (document) {
        is ReadingDocument.Pdf -> ReadingPdfView(document, locator, searchQuery, onLocatorChanged, onError, modifier, onCenterTap)
        is ReadingDocument.Epub -> AndroidReadingEpub(document, locator, preferences, searchQuery, onLocatorChanged, onError, modifier, onCenterTap, control)
    }
}

/** What evaluateJavascript hands back (a JSON value) as the string the script gave, or null. */
private fun scriptResult(raw: String?): String? = runCatching {
    when (val value = Json.parseToJsonElement(raw ?: "null")) { is JsonNull -> null; is JsonPrimitive -> value.content; else -> value.toString() }
}.getOrNull()

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun AndroidReadingEpub(document: ReadingDocument.Epub, locator: ReadingLocator,
    preferences: ReadingPreferences, searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit,
    onError: (String) -> Unit, modifier: Modifier, onCenterTap: () -> Unit, control: ReadingPageControl?) {
    val latestTap by rememberUpdatedState(onCenterTap)
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val content = remember(document) { ReadingEpubContent(document, "https://cosmotune-publication.invalid/") }
    val latestPrefs by rememberUpdatedState(preferences)
    val latestDark by rememberUpdatedState(dark)
    val latestQuery by rememberUpdatedState(searchQuery)
    val latestLocator by rememberUpdatedState(onLocatorChanged)
    val latestError by rememberUpdatedState(onError)
    var requested by remember(document) { mutableStateOf(locator) }
    var lastEmitted by remember(document) { mutableStateOf<ReadingLocator?>(null) }
    var loadedHref by remember(document) { mutableStateOf<String?>(null) }
    var loaded by remember(document) { mutableStateOf(false) }
    var matches by remember(document) { mutableStateOf(0) }
    val web = remember(document, context) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true // Only sanitized markup and app-authored evaluateJavascript; no JS interfaces.
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                domStorageEnabled = false
                databaseEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = true
                displayZoomControls = false
            }
            isVerticalScrollBarEnabled = true
            // A single tap on the middle third shows or hides the reader's tools; the page still scrolls, selects and follows links.
            val taps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                    if (event.x > width / 3f && event.x < width * 2f / 3f) latestTap()
                    else if (control?.tapZones == true) {
                        val forward = (event.x >= width * 2f / 3f) != (control.invertZones)
                        evaluateJavascript(ReadingEpubScripts.page(forward, control.slide), null); control.onTurn?.invoke(forward)
                    }
                    return false
                }
            })
            // A swipe down or up the page's left edge dims or brightens it; anywhere else the page scrolls as usual.
            var edgeY: Float? = null
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                taps.onTouchEvent(event)
                val brightness = control?.onBrightness
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> edgeY = if (brightness != null && event.x < width * 0.06f) event.y else null
                    android.view.MotionEvent.ACTION_MOVE -> edgeY?.let { start ->
                        val delta = (start - event.y) / height.coerceAtLeast(1)
                        if (kotlin.math.abs(delta) > 0.01f) { brightness?.invoke(delta * 1.5f); edgeY = event.y }
                        return@setOnTouchListener true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (edgeY != null) { edgeY = null; return@setOnTouchListener true }
                }
                false
            }
            // Page-turn pedals and keyboards (Page Down, arrows, space) and, when chosen, the volume keys turn the page.
            setOnKeyListener { _, code, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener code in pageKeys || (control?.volumeKeys == true && code in volumeKeys)
                val forward = when (code) {
                    android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_SPACE -> true
                    android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> false
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (control?.volumeKeys == true) true else return@setOnKeyListener false
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (control?.volumeKeys == true) false else return@setOnKeyListener false
                    else -> return@setOnKeyListener false
                }
                evaluateJavascript(ReadingEpubScripts.page(forward, control?.slide == true), null); control?.onTurn?.invoke(forward)
                true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val href = request?.url?.toString()?.let(content::href)
                    val resource = href?.let(content::resource)
                    return if (resource != null) WebResourceResponse(resource.first.substringBefore(';'), "UTF-8", ByteArrayInputStream(resource.second))
                    else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val internal = request?.url?.toString()?.let(content::href) ?: return true
                    if (request.isForMainFrame) requested = ReadingLocator(internal)
                    return false
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loaded = false }
                override fun onPageFinished(view: WebView, url: String) {
                    loadedHref = content.href(url)?.substringBefore('#')
                    loaded = loadedHref != null
                    if (!loaded) return
                    view.evaluateJavascript(ReadingEpubScripts.preferences(latestPrefs, latestDark, content.origin), null)
                    view.evaluateJavascript(ReadingEpubScripts.restore(requested), null)
                    control?.loaded(loadedHref)
                    view.postDelayed({
                        if (loaded) {
                            view.evaluateJavascript(ReadingEpubScripts.restore(requested), null)
                            view.evaluateJavascript(ReadingEpubScripts.search(latestQuery)) { matches = it.toIntOrNull() ?: 0 }
                        }
                    }, 250)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) latestError(error?.description?.toString() ?: "Cannot display this EPUB section")
                }
            }
        }
    }
    DisposableEffect(web) { onDispose { control?.evaluator = null; web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy() } }
    DisposableEffect(web, control) {
        control?.evaluator = { script -> if (!loaded) null else kotlinx.coroutines.suspendCancellableCoroutine { done ->
            web.evaluateJavascript(script) { raw -> if (done.isActive) done.resumeWith(Result.success(scriptResult(raw))) }
        } }
        onDispose { control?.evaluator = null }
    }
    LaunchedEffect(web, locator) {
        if (locator == lastEmitted) return@LaunchedEffect
        requested = locator
        val href = locator.resourceHref?.substringBefore('#')?.takeIf { it in document.resources } ?: document.spine.first().href
        if (href != loadedHref) {
            loaded = false
            loadedHref = href
            web.loadUrl(content.url(locator.resourceHref?.takeIf { it.substringBefore('#') == href } ?: href))
        } else if (loaded) web.evaluateJavascript(ReadingEpubScripts.restore(locator), null)
    }
    LaunchedEffect(web, preferences, dark) { if (loaded) {
        web.evaluateJavascript(ReadingEpubScripts.preferences(preferences, dark, content.origin), null)
        web.evaluateJavascript(ReadingEpubScripts.restore(lastEmitted ?: requested), null)
    } }
    LaunchedEffect(web, searchQuery) { if (loaded) web.evaluateJavascript(ReadingEpubScripts.search(searchQuery)) { matches = it.toIntOrNull() ?: 0 } }
    LaunchedEffect(web) {
        while (isActive) {
            delay(650)
            if (loaded) web.evaluateJavascript(ReadingEpubScripts.position) { encoded ->
                runCatching {
                    val json = Json.parseToJsonElement(encoded).jsonPrimitive.content
                    val position = Json.parseToJsonElement(json).jsonObject
                    val href = web.url?.let(content::href)?.substringBefore('#') ?: return@runCatching
                    val next = ReadingLocator(href, position["progression"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0,
                        scrollOffset = position["scrollOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    loadedHref = href
                    if (next != lastEmitted) { lastEmitted = next; latestLocator(next) }
                }
            }
        }
    }
    Column(modifier) {
        if (searchQuery.isNotBlank()) Text("$matches matches in this section", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        if (!preferences.scrollMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReadTextButton("Previous screen", { web.evaluateJavascript(ReadingEpubScripts.page(false, preferences.slideTurn && !preferences.eInk), null); control?.onTurn?.invoke(false) })
            ReadTextButton("Next screen", { web.evaluateJavascript(ReadingEpubScripts.page(true, preferences.slideTurn && !preferences.eInk), null); control?.onTurn?.invoke(true) })
        }
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}

/** The kinds of file Read imports; Kindle and some other books carry no type on Android, so any file can be picked and is checked on import. */
private val READING_IMPORT_TYPES = arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
    "application/x-fictionbook+xml", "text/plain", "text/html", "text/markdown", "application/rtf", "text/rtf", "multipart/related", "message/rfc822",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.comicbook+zip", "application/x-cbz", "application/zip",
    "application/octet-stream")

private val pageKeys = setOf(android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_SPACE)
private val volumeKeys = setOf(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP)

internal actual fun openReadingPdf(file: File): ReadingPdfEngine = AndroidReadingPdf(file)
internal actual fun supportsReadingPdfSearch(): Boolean = android.os.Build.VERSION.SDK_INT >= 35
private class AndroidReadingPdf(file: File) : ReadingPdfEngine {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    override val pageCount: Int = renderer.pageCount
    override val supportsSearch: Boolean get() = supportsReadingPdfSearch()
    @Synchronized override fun render(page: Int, width: Int): ImageBitmap = renderer.openPage(page).use { source ->
        val scale = minOf(width.coerceIn(320, 2400).toDouble() / source.width, kotlin.math.sqrt(8_000_000.0 / (source.width.toDouble() * source.height)))
        val bitmap = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap.asImageBitmap()
    }
    @Synchronized override fun find(query: String, afterPage: Int): Int? {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        for (offset in 0 until pageCount) {
            val page = (afterPage + offset).mod(pageCount)
            renderer.openPage(page).use { if (it.searchText(query).isNotEmpty()) return page }
        }
        return null
    }
    @Synchronized override fun close() { renderer.close(); descriptor.close() }
}

@Composable actual fun rememberReadingSave(onError: (String) -> Unit): (String, String, ByteArray) -> Unit {
    val context = LocalContext.current
    val latestError by rememberUpdatedState(onError)
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }
            .onFailure { latestError("The file could not be saved there.") }
    }
    return remember(picker) { { name, _, bytes -> pending = bytes; picker.launch(name) } }
}

@Composable actual fun rememberReadingImport(kind: ReadingPick, onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Book.epub"
            latest(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this document" } }
        }
    }
    return remember(picker, kind) { { picker.launch(when (kind) {
        ReadingPick.DICTIONARY -> arrayOf("application/zip", "application/gzip", "application/x-gzip", "application/octet-stream")
        ReadingPick.FONT -> arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/x-font-ttf", "application/x-font-otf", "application/font-woff", "application/octet-stream")
        ReadingPick.BOOK -> READING_IMPORT_TYPES
    }) } }
}
