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
    LaunchedEffect(document.file) {
        var opening: ReadingPdfEngine? = null
        try {
            engine = withContext(Dispatchers.IO) { openReadingPdf(document.file).also { opening = it } }
            opening = null
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "Cannot open PDF"; latestError(failure!!) }
        finally { opening?.close() }
    }
    DisposableEffect(engine) { val owned = engine; onDispose {
        if (owned != null) CoroutineScope(Dispatchers.IO).launch { runCatching { owned.close() } }
    } }
    val pageCount = engine?.pageCount ?: 0
    val page = locator.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    fun navigate(index: Int) = latestLocator(ReadingLocator(page = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        progression = if (pageCount <= 1) 0.0 else index.toDouble() / (pageCount - 1)))
    LaunchedEffect(engine, searchQuery, searchNext) {
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
    }
    Column(modifier) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { navigate(page - 1) }, enabled = page > 0) { Text("Previous page") }
            Text(if (pageCount > 0) "${page + 1} / $pageCount" else "Opening…", Modifier.heightIn(min = 48.dp).wrapContentHeight())
            TextButton(onClick = { zoom = (zoom - .25f).coerceAtLeast(.5f) }) { Text("−") }
            Text("${(zoom * 100).toInt()}%", Modifier.heightIn(min = 48.dp).wrapContentHeight())
            TextButton(onClick = { zoom = (zoom + .25f).coerceAtMost(4f) }) { Text("+") }
            TextButton(onClick = { navigate(page + 1) }, enabled = page + 1 < pageCount) { Text("Next page") }
            if (searchQuery.isNotBlank() && engine?.supportsSearch == true) TextButton(onClick = { searchNext++ }) { Text("Next match") }
        }
        searchMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = maxWidth
            val targetWidth = (constraints.maxWidth * zoom).toInt().coerceIn(320, 2400)
            LaunchedEffect(engine, page, targetWidth) {
                image = null
                val owned = engine ?: return@LaunchedEffect
                try { image = withContext(Dispatchers.IO) { owned.render(page, targetWidth) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: "Cannot render PDF page"; latestError(failure!!) }
            }
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
