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
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.ui.platform.testTag
import com.cosmotune.reading.data.*
import java.io.File
import java.io.InputStream
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    /** A tap on the middle third of the page (the reader's tools), where the platform's page can tell; see [readingCenterTap]. */
    onCenterTap: () -> Unit = {},
    /** Scripts the reader runs against the open chapter (selection, highlights, reading aloud); EPUB only. */
    control: ReadingPageControl? = null,
)

/**
 * What the reader asks of a book's page besides showing it. The platform page sets [evaluator] while it can run the app's own
 * scripts (never the book's) and counts [loads] each time a chapter finishes loading, so highlights can be laid on again.
 */
class ReadingPageControl {
    internal var evaluator: (suspend (String) -> String?)? = null
    var loads by mutableIntStateOf(0)
        internal set
    var href by mutableStateOf<String?>(null)
        internal set
    /** Runs [script] (an expression giving a string) on the open chapter; null when the page cannot run it now. */
    suspend fun run(script: String): String? = try { evaluator?.invoke(script) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    internal fun loaded(href: String?) { this.href = href; loads++ }
    /** Side taps that turn the page (and the other way round), volume keys and keyboard keys that turn it, a slide on turning,
     *  and a swipe down the left edge for brightness ([onBrightness] hears the change, -1 to 1). */
    var tapZones = true
    var invertZones = false
    var volumeKeys = false
    var slide = true
    /** Turning like paper: the page swings over from its edge (see [ReadingEpubScripts.page]). */
    var curl = false
    var onBrightness: ((Float) -> Unit)? = null
    /** Turns a screen on or back from the platform page (keys, taps); also counts toward a refresh in e-ink mode. */
    var onTurn: ((Boolean) -> Unit)? = null
}

/** Whether a tap on the middle of the page reaches [ReadingDocumentView]'s onCenterTap; where it does not, the tools keep their button. */
internal expect val readingCenterTap: Boolean

/** What a picker of Read's asks for: a book, a StarDict dictionary pack, or a font. */
enum class ReadingPick(val title: String, val extensions: Set<String>) {
    BOOK("Import a book", setOf("epub", "pdf")), DICTIONARY("Add a dictionary", setOf("zip", "gz", "tgz")), FONT("Add a font", setOf("ttf", "otf", "woff", "woff2"))
}

/** A file picker for [kind]. */
@Composable expect fun rememberReadingImport(kind: ReadingPick = ReadingPick.BOOK, onPicked: (String, () -> InputStream) -> Unit): () -> Unit

/** Asks where to keep a file (a book's notes) and writes it there: (suggested name, type, bytes). [onError] hears a failed write. */
@Composable expect fun rememberReadingSave(onError: (String) -> Unit): (String, String, ByteArray) -> Unit

/** Native engines own their PDF and render one page at a time, with a fixed raster budget. */
internal interface ReadingPdfEngine : AutoCloseable {
    val pageCount: Int
    fun render(page: Int, width: Int): ImageBitmap
    fun find(query: String, afterPage: Int): Int? = null
    val supportsSearch: Boolean get() = false
}
internal expect fun openReadingPdf(file: File): ReadingPdfEngine
internal expect fun supportsReadingPdfSearch(): Boolean
/** Each page's text, for a text copy of a PDF; null where the device cannot read PDF text. */
internal expect fun readingPdfText(file: File): List<String>?

/** The part of [image] inside its white margins (a small border kept), or null when there is nothing to trim. */
internal fun pdfContentBounds(image: ImageBitmap): androidx.compose.ui.unit.IntRect? {
    val width = image.width; val height = image.height
    if (width < 32 || height < 32) return null
    val pixels = IntArray(width * height)
    image.readPixels(pixels)
    fun ink(x: Int, y: Int): Boolean { val p = pixels[y * width + x]; return ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) < 690 }
    val step = maxOf(1, minOf(width, height) / 400)
    var left = width; var right = -1; var top = height; var bottom = -1
    var y = 0
    while (y < height) { var x = 0; while (x < width) { if (ink(x, y)) { if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; if (y > bottom) bottom = y }; x += step }; y += step }
    if (right < 0) return null
    val pad = minOf(width, height) / 50
    val rect = androidx.compose.ui.unit.IntRect((left - pad).coerceAtLeast(0), (top - pad).coerceAtLeast(0), (right + pad).coerceAtMost(width), (bottom + pad).coerceAtMost(height))
    return rect.takeIf { it.width < width * 0.97f || it.height < height * 0.97f }
}

/**
 * The columns of text on a page ([pixels] row by row, [width] wide), left to right, as the part of the page each takes (a small
 * border kept); empty when the page is one column. A column is split off where a clear upright gap of paper runs down the printed part.
 */
internal fun pdfColumns(pixels: IntArray, width: Int, height: Int): List<androidx.compose.ui.unit.IntRect> {
    if (width < 64 || height < 64) return emptyList()
    fun ink(p: Int) = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) < 600
    val step = maxOf(1, height / 600)
    val counts = IntArray(width)
    var top = height; var bottom = -1
    var y = 0
    while (y < height) {
        var any = false
        for (x in 0 until width) if (ink(pixels[y * width + x])) { counts[x]++; any = true }
        if (any) { if (y < top) top = y; bottom = y }
        y += step
    }
    if (bottom < 0) return emptyList()
    val left = counts.indexOfFirst { it > 0 }; val right = counts.indexOfLast { it > 0 }
    // A gutter: a run of nearly blank upright lines at least 2 % of the page wide, inside the printed part (headers crossing it are allowed).
    val rows = (bottom - top) / step + 1
    val blank = maxOf(1, rows / 40)
    val minGap = maxOf(4, width / 50)
    val spans = mutableListOf<IntRange>()
    var start = left; var x = left; var gapFrom = -1
    while (x <= right) {
        if (counts[x] <= blank) { if (gapFrom < 0) gapFrom = x }
        else { if (gapFrom >= 0 && x - gapFrom >= minGap && gapFrom > start) { spans += start until gapFrom; start = x }; gapFrom = -1 }
        x++
    }
    spans += start..right
    // Columns narrower than a sixth of the printed width are figures or margins notes, not text columns.
    val printed = right - left + 1
    val columns = spans.filter { it.last - it.first + 1 >= printed / 6 }
    if (columns.size < 2) return emptyList()
    val pad = width / 100
    return columns.map { androidx.compose.ui.unit.IntRect((it.first - pad).coerceAtLeast(0), (top - pad).coerceAtLeast(0), (it.last + pad + 1).coerceAtMost(width), (bottom + step + pad).coerceAtMost(height)) }
}

/** [pdfColumns] of a rendered page. */
internal fun pdfColumns(image: ImageBitmap): List<androidx.compose.ui.unit.IntRect> {
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    return pdfColumns(pixels, image.width, image.height)
}

/** Light on dark (inverted) and the contrast, as one colour filter for a PDF page; null when the page stays as printed. */
internal fun pdfFilter(dark: Boolean, contrast: Float): androidx.compose.ui.graphics.ColorFilter? {
    if (!dark && contrast == 1f) return null
    val c = contrast.coerceIn(0.5f, 2f); val sign = if (dark) -1f else 1f
    val shift = if (dark) 255f * (1 - (1 - c) / 2f) else 255f * (1 - c) / 2f
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        sign * c, 0f, 0f, 0f, shift, 0f, sign * c, 0f, 0f, shift, 0f, 0f, sign * c, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReadingPdfView(document: ReadingDocument.Pdf, locator: ReadingLocator,
    searchQuery: String, onLocatorChanged: (ReadingLocator) -> Unit, onError: (String) -> Unit,
    modifier: Modifier, onCenterTap: () -> Unit = {}, preferences: ReadingPreferences = ReadingPreferences()) {
    var engine by remember(document.file) { mutableStateOf<ReadingPdfEngine?>(null) }
    var failure by remember(document.file) { mutableStateOf<String?>(null) }
    var zoom by remember(document.file) { mutableStateOf(1f) }
    // Zoom to a column: one column of a page at a time across the screen, the arrows going through the columns before the page turns.
    var byColumn by remember(document.file) { mutableStateOf(false) }
    var column by remember(document.file) { mutableStateOf(0) }
    var columnCount by remember(document.file) { mutableStateOf(0) }
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
            val inColumns = byColumn && columnCount > 1
            com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.ChevronLeft, if (inColumns) "Previous column" else "Previous page", look, {
                if (inColumns && column > 0) column-- else { column = if (byColumn) -1 else 0; navigate(page - 1) }
            }, size = 36.dp, enabled = page > 0 || (inColumns && column > 0))
            com.cosmotune.ui.orbit.OrbitLabel(if (pageCount == 0) "Opening…" else if (inColumns) "Page ${page + 1} of $pageCount · column ${column.coerceAtLeast(0) + 1} of $columnCount"
                else "Page ${page + 1} of $pageCount · ${(zoom * 100).toInt()} %", fonts, look.dim,
                Modifier.heightIn(min = 36.dp).wrapContentHeight())
            com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.ChevronRight, if (inColumns) "Next column" else "Next page", look, {
                if (inColumns && column + 1 < columnCount) column++ else { column = 0; navigate(page + 1) }
            }, size = 36.dp, enabled = page + 1 < pageCount || (inColumns && column + 1 < columnCount))
            com.cosmotune.ui.orbit.WatchRoundButton(androidx.compose.material.icons.Icons.Rounded.ZoomOut, "Zoom out", look, { zoom = (zoom - .25f).coerceAtLeast(.5f) }, size = 36.dp)
            com.cosmotune.ui.orbit.WatchRoundButton(androidx.compose.material.icons.Icons.Rounded.ZoomIn, "Zoom in", look, { zoom = (zoom + .25f).coerceAtMost(4f) }, size = 36.dp)
            com.cosmotune.ui.orbit.WatchRoundButton(androidx.compose.material.icons.Icons.Rounded.ViewColumn, if (byColumn) "Show whole pages" else "Zoom to a column", look,
                { byColumn = !byColumn; column = 0 }, size = 36.dp, on = byColumn, modifier = Modifier.testTag("books:pdf-columns"))
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
                    val shown = image!!
                    val columns = remember(shown, byColumn) { if (byColumn) pdfColumns(shown) else emptyList() }
                    SideEffect { columnCount = columns.size; if (column < 0 || column >= columns.size) column = if (column < 0) (columns.size - 1).coerceAtLeast(0) else 0 }
                    val crop = remember(shown, preferences.pdfCrop, columns, column) { columns.getOrNull(column) ?: if (preferences.pdfCrop) pdfContentBounds(shown) else null }
                    val painter = remember(shown, crop) {
                        if (crop == null) androidx.compose.ui.graphics.painter.BitmapPainter(shown)
                        else androidx.compose.ui.graphics.painter.BitmapPainter(shown, androidx.compose.ui.unit.IntOffset(crop.left, crop.top), androidx.compose.ui.unit.IntSize(crop.width, crop.height))
                    }
                    val ratio = if (crop != null) crop.width.toFloat() / crop.height else shown.width.toFloat() / shown.height
                    Image(painter, "Page ${page + 1} of ${document.book.title}",
                        Modifier.width(viewportWidth * zoom).aspectRatio(ratio)
                            .pointerInput(Unit) { detectTapGestures { at -> if (at.x > size.width / 3f && at.x < size.width * 2f / 3f) onCenterTap() } },
                        contentScale = ContentScale.FillBounds, colorFilter = pdfFilter(preferences.pdfDark, preferences.pdfContrast))
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}
