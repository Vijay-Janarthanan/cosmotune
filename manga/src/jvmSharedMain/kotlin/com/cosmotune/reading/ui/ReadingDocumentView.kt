package com.cosmotune.reading.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import com.cosmotune.reading.data.*
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable expect fun ReadingDocumentView(
    document: ReadingDocument,
    locator: ReadingLocator,
    preferences: ReadingPreferences,
    searchQuery: String,
    onLocatorChanged: (ReadingLocator) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable expect fun rememberReadingImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit

/** Native engines own their PDF and render one page at a time, with a fixed raster budget. */
internal interface ReadingPdfEngine : AutoCloseable {
    val pageCount: Int
    fun render(page: Int, width: Int): ImageBitmap
    fun find(query: String, afterPage: Int): Int? = null
    val supportsSearch: Boolean get() = false
}
internal expect fun openReadingPdf(file: File): ReadingPdfEngine

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReadingPdfView(document: ReadingDocument.Pdf, locator: ReadingLocator,
    searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit, onError: (String) -> Unit,
    modifier: Modifier) {
    var engine by remember(document.file) { mutableStateOf<ReadingPdfEngine?>(null) }
    var failure by remember(document.file) { mutableStateOf<String?>(null) }
    var zoom by remember(document.file) { mutableStateOf(1f) }
    var image by remember(document.file) { mutableStateOf<ImageBitmap?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searchNext by remember { mutableStateOf(0) }
    val latestError by rememberUpdatedState(onError)
    val latestLocator by rememberUpdatedState(onLocatorChanged)
    // The engine, the search line and the page image are set on the UI thread (see com.cosmotune.ui.rememberUiCoroutineScope).
    LaunchedEffect(document.file) { withContext(Dispatchers.Main.immediate) {
        var opening: ReadingPdfEngine? = null
        try {
            engine = withContext(Dispatchers.IO) { openReadingPdf(document.file).also { opening = it } }
            opening = null
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "Cannot open PDF"; latestError(failure!!) }
        finally { opening?.close() }
    } }
    DisposableEffect(engine) { val owned = engine; onDispose {
        if (owned != null) CoroutineScope(Dispatchers.IO).launch { runCatching { owned.close() } }
    } }
    val pageCount = engine?.pageCount ?: 0
    val page = locator.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    fun navigate(index: Int) = latestLocator(ReadingLocator(page = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        progression = if (pageCount <= 1) 0.0 else index.toDouble() / (pageCount - 1)))
    LaunchedEffect(engine, searchQuery, searchNext) { withContext(Dispatchers.Main.immediate) {
        searchMessage = null
        if (searchQuery.isNotBlank() && engine != null) {
            if (engine?.supportsSearch != true) searchMessage = "PDF search needs Android 15 or later. EPUB text search is available on this device."
            else {
                searching = true
                try {
                    val found = withContext(Dispatchers.IO) { engine?.find(searchQuery, if (searchNext > 0) (page + 1).mod(pageCount) else page) }
                    if (found != null) { navigate(found); searchMessage = "Match on page ${found + 1}" }
                    else searchMessage = "No matching text in this PDF"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { searchMessage = e.message ?: "Search failed" }
                finally { searching = false }
            }
        }
    } }
    Column(modifier) {
        // The publisher's page keeps its own quiet strip: the page and the zoom as a mono line between round buttons.
        val look = com.cosmotune.ui.orbit.orbitLook()
        val fonts = com.cosmotune.ui.orbit.orbitFonts()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.ChevronLeft, "Previous page", look, { navigate(page - 1) }, size = 36.dp, enabled = page > 0)
            com.cosmotune.ui.orbit.OrbitLabel(if (pageCount > 0) "Page ${page + 1} of $pageCount · ${(zoom * 100).toInt()} %" else "Opening…", fonts, look.dim,
                Modifier.heightIn(min = 36.dp).wrapContentHeight())
            com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.ChevronRight, "Next page", look, { navigate(page + 1) }, size = 36.dp, enabled = page + 1 < pageCount)
            com.cosmotune.ui.orbit.WatchRoundButton(androidx.compose.material.icons.Icons.Rounded.ZoomOut, "Zoom out", look, { zoom = (zoom - .25f).coerceAtLeast(.5f) }, size = 36.dp)
            com.cosmotune.ui.orbit.WatchRoundButton(androidx.compose.material.icons.Icons.Rounded.ZoomIn, "Zoom in", look, { zoom = (zoom + .25f).coerceAtMost(4f) }, size = 36.dp)
            if (searchQuery.isNotBlank() && engine?.supportsSearch == true)
                com.cosmotune.ui.orbit.WatchCardChip("Next match", look.ink, fonts, { searchNext++ }, icon = com.cosmotune.ui.components.CosmoIcons.Search)
        }
        searchMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = fonts.body, color = look.dim) }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = maxWidth
            val targetWidth = (constraints.maxWidth * zoom).toInt().coerceIn(320, 2400)
            LaunchedEffect(engine, page, targetWidth) { withContext(Dispatchers.Main.immediate) {
                image = null
                val owned = engine ?: return@withContext
                try { image = withContext(Dispatchers.IO) { owned.render(page, targetWidth) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: "Cannot render PDF page"; latestError(failure!!) }
            } }
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            LaunchedEffect(page) { vertical.scrollTo(0); horizontal.scrollTo(0) }
            when {
                failure != null -> Text(failure!!, Modifier.padding(24.dp))
                image != null -> Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.TopCenter) {
                    Image(image!!, "Page ${page + 1} of ${document.book.title}",
                        Modifier.width(viewportWidth * zoom).aspectRatio(image!!.width.toFloat() / image!!.height), contentScale = ContentScale.FillBounds)
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}
