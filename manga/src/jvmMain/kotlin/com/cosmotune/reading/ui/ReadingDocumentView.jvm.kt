package com.cosmotune.reading.ui

import com.cosmotune.manga.ui.ReadTextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.cosmotune.reading.data.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable actual fun ReadingDocumentView(document: ReadingDocument, locator: ReadingLocator,
    preferences: ReadingPreferences, searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit,
    onError: (String) -> Unit, modifier: Modifier) {
    when (document) {
        is ReadingDocument.Pdf -> ReadingPdfView(document, locator, searchQuery, onLocatorChanged, onError, modifier)
        is ReadingDocument.Epub -> DesktopReadingEpub(document, locator, preferences, searchQuery, onLocatorChanged, onError, modifier)
    }
}

@Composable private fun DesktopReadingEpub(document: ReadingDocument.Epub, locator: ReadingLocator,
    preferences: ReadingPreferences, searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit,
    onError: (String) -> Unit, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val latestLocator by rememberUpdatedState(onLocatorChanged)
    val latestError by rememberUpdatedState(onError)
    val latestPrefs by rememberUpdatedState(preferences)
    val latestSearch by rememberUpdatedState(searchQuery)
    val latestDark by rememberUpdatedState(dark)
    val scope = rememberCoroutineScope()
    val controller = remember(document) { DesktopEpubController(document) }
    var ready by remember(controller) { mutableStateOf(false) }
    var loaded by remember(controller) { mutableStateOf(false) }
    var matches by remember(controller) { mutableStateOf(0) }
    var loadedHref by remember(controller) { mutableStateOf<String?>(null) }
    var lastEmitted by remember(controller) { mutableStateOf<ReadingLocator?>(null) }
    var requested by remember(controller) { mutableStateOf(locator) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) {
        try {
            controller.awaitReady()
            controller.fx {
                engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (state == Worker.State.SUCCEEDED) {
                        scope.launch {
                            val actual = controller.content.href(controller.engine.location)
                            loadedHref = actual?.substringBefore('#')
                            val desired = requested.resourceHref?.substringBefore('#') ?: document.spine.first().href
                            if (actual != null && desired != loadedHref) requested = ReadingLocator(actual)
                            loaded = loadedHref != null
                            controller.fx { engine.executeScript(ReadingEpubScripts.preferences(latestPrefs, latestDark)); engine.executeScript(ReadingEpubScripts.restore(requested)) }
                            delay(200)
                            controller.fx { engine.executeScript(ReadingEpubScripts.restore(requested)) }
                            ready = true
                            matches = (controller.fx { engine.executeScript(ReadingEpubScripts.search(latestSearch)) } as? Number)?.toInt() ?: 0
                        }
                    } else if (state == Worker.State.SCHEDULED) loaded = false
                    else if (state == Worker.State.FAILED) scope.launch { latestError("Cannot display this EPUB section") }
                }
                engine.locationProperty().addListener { _, _, location ->
                    val internal = controller.content.href(location)
                    if (location.isNotBlank() && internal == null) engine.loadWorker.cancel()
                    else if (internal != null && loaded) requested = ReadingLocator(internal)
                }
            }
            ready = true
        } catch (e: Exception) { latestError(e.message ?: "Cannot start desktop book reader") }
    }
    LaunchedEffect(controller, ready, locator) {
        if (!ready || locator == lastEmitted) return@LaunchedEffect
        requested = locator
        val href = locator.resourceHref?.substringBefore('#')?.takeIf { it in document.resources } ?: document.spine.first().href
        if (href != loadedHref) {
            loaded = false
            loadedHref = href
            controller.fx { engine.load(controller.content.url(locator.resourceHref?.takeIf { it.substringBefore('#') == href } ?: href)) }
        } else controller.fx { engine.executeScript(ReadingEpubScripts.restore(locator)) }
    }
    LaunchedEffect(controller, preferences, dark) { if (ready && loadedHref != null) controller.fx { engine.executeScript(ReadingEpubScripts.preferences(preferences, dark)); engine.executeScript(ReadingEpubScripts.restore(lastEmitted ?: requested)) } }
    LaunchedEffect(controller, searchQuery) { if (ready && loadedHref != null) matches = (controller.fx { engine.executeScript(ReadingEpubScripts.search(searchQuery)) } as? Number)?.toInt() ?: 0 }
    LaunchedEffect(controller) {
        while (isActive) {
            delay(650)
            if (!ready || !loaded || loadedHref == null) continue
            runCatching {
                val result = controller.fx { engine.executeScript(ReadingEpubScripts.position)?.toString() }
                val values = Json.parseToJsonElement(result ?: "{}").jsonObject
                val currentHref = controller.content.href(controller.fx { engine.location })?.substringBefore('#') ?: return@runCatching
                val next = ReadingLocator(currentHref, values["progression"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0,
                    scrollOffset = values["scrollOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                loadedHref = currentHref
                if (next != lastEmitted) { lastEmitted = next; latestLocator(next) }
            }
        }
    }
    Column(modifier) {
        if (searchQuery.isNotBlank()) Text("$matches matches in this section", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        if (!preferences.scrollMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReadTextButton("Previous screen", { scope.launch { controller.fx { engine.executeScript("window.scrollBy(0,-window.innerHeight*0.88)") } } })
            ReadTextButton("Next screen", { scope.launch { controller.fx { engine.executeScript("window.scrollBy(0,window.innerHeight*0.88)") } } })
        }
        SwingPanel(factory = { controller.panel }, modifier = Modifier.fillMaxSize())
    }
}

private class DesktopEpubController(document: ReadingDocument.Epub) : AutoCloseable {
    val server = ReadingEpubServer(document)
    val content get() = server.content
    val panel = JFXPanel()
    private val started = CompletableFuture<Unit>()
    lateinit var web: WebView
    val engine get() = web.engine
    init {
        Platform.setImplicitExit(false)
        Platform.runLater {
            try {
                web = WebView()
                web.isContextMenuEnabled = false
                web.engine.isJavaScriptEnabled = true // Sanitized content; only app executeScript calls run.
                web.engine.createPopupHandler = javafx.util.Callback { null }
                web.engine.setOnAlert { }
                web.engine.confirmHandler = javafx.util.Callback { false }
                panel.scene = Scene(web)
                started.complete(Unit)
            } catch (e: Throwable) { started.completeExceptionally(e) }
        }
    }
    suspend fun awaitReady() = withContext(Dispatchers.IO) { started.get() }
    suspend fun <T> fx(action: DesktopEpubController.() -> T): T = suspendCancellableCoroutine { continuation ->
        Platform.runLater { if (continuation.isActive) try { continuation.resume(action()) } catch (e: Throwable) { continuation.resumeWithException(e) } }
    }
    override fun close() {
        server.close()
        Platform.runLater { if (::web.isInitialized) { engine.loadWorker.cancel(); engine.load(null); panel.scene = null } }
    }
}

internal actual fun openReadingPdf(file: File): ReadingPdfEngine = DesktopReadingPdf(file)
internal class DesktopReadingPdf(file: File) : ReadingPdfEngine {
    private val pdf: PDDocument = Loader.loadPDF(file, org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())
    private val renderer = PDFRenderer(pdf).apply { isSubsamplingAllowed = true }
    override val pageCount: Int = pdf.numberOfPages
    override val supportsSearch = true
    @Synchronized override fun render(page: Int, width: Int): ImageBitmap {
        val bounds = pdf.getPage(page).cropBox
        val rotated = pdf.getPage(page).rotation % 180 != 0
        val pageWidth = if (rotated) bounds.height else bounds.width
        val pageHeight = if (rotated) bounds.width else bounds.height
        require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0) { "Invalid PDF page dimensions" }
        val scale = minOf(width.coerceIn(320, 2400).toDouble() / pageWidth,
            kotlin.math.sqrt(8_000_000.0 / (pageWidth.toDouble() * pageHeight))).toFloat()
        return renderer.renderImage(page, scale, org.apache.pdfbox.rendering.ImageType.RGB).toComposeImageBitmap()
    }
    @Synchronized override fun find(query: String, afterPage: Int): Int? {
        val stripper = PDFTextStripper()
        for (offset in 0 until pageCount) {
            val page = (afterPage + offset).mod(pageCount)
            stripper.startPage = page + 1; stripper.endPage = page + 1
            if (stripper.getText(pdf).contains(query, ignoreCase = true)) return page
        }
        return null
    }
    @Synchronized override fun close() { pdf.close() }
}

@Composable actual fun rememberReadingImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val selected = withContext(Dispatchers.IO) {
            var chosen: File? = null
            SwingUtilities.invokeAndWait {
                val picker = FileDialog(null as Frame?, "Import EPUB or PDF", FileDialog.LOAD)
                try {
                    picker.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("epub", "pdf") }
                    picker.isVisible = true
                    picker.file?.let { chosen = File(picker.directory, it) }
                } finally { picker.dispose() }
            }
            chosen
        }
        selected?.let { latest(it.name) { it.inputStream() } }
    }; Unit } }
}
