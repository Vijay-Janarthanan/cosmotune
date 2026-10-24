package com.cosmotune.manga.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import com.cosmotune.manga.data.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull

@Composable
internal fun MangaReader(repository: MangaRepository, chapter: MangaChapter, hasNext: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    key(chapter.id) {
        MangaBackHandler(true, onBack)
        var pages by remember { mutableStateOf(emptyList<MangaPage>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var controls by rememberSaveable { mutableStateOf(true) }
        val saved = remember { repository.store.state.value.progress[chapter.bookId]?.takeIf { it.chapterId == chapter.id } }
        var mode by rememberSaveable { mutableStateOf(saved?.mode ?: repository.store.state.value.readerMode) }
        val library by repository.store.state.collectAsState()
        val fit = library.readerFit
        val scope = rememberCoroutineScope()
        // Zoom per page, shared by gestures, keys and the buttons; a page opens at 100 %.
        val zoom = remember { mutableStateMapOf<String, Float>() }
        var current by remember { mutableStateOf<String?>(null) }
        // Reader keys keep working after a control is clicked: the controls hand focus back to the pages.
        val focus = remember { FocusRequester() }
        fun zoomBy(factor: Float) {
            current?.let { id -> zoom[id] = ((zoom[id] ?: 1f) * factor).coerceIn(1f, 5f) }
            runCatching { focus.requestFocus() }
        }
        LaunchedEffect(retry) {
            loading = true; error = null
            try { pages = repository.pages(chapter); check(pages.isNotEmpty()) { "No pages are available for this chapter" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Chapter could not be loaded" }
            finally { loading = false }
        }
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (controls) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(chapter.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text(when (mode) { MangaReaderMode.PAGES_LTR -> "Left to right"; MangaReaderMode.PAGES_RTL -> "Right to left"; MangaReaderMode.WEBTOON -> "Webtoon" }) }
                    DropdownMenu(menu, { menu = false }) {
                        MangaReaderMode.entries.forEach { item -> DropdownMenuItem(text = { Text(when (item) {
                            MangaReaderMode.PAGES_LTR -> "Left to right"; MangaReaderMode.PAGES_RTL -> "Right to left"; MangaReaderMode.WEBTOON -> "Webtoon"
                        }) }, onClick = { mode = item; menu = false }) }
                    }
                }
                if (mode != MangaReaderMode.WEBTOON) TextButton(onClick = { scope.launch {
                    try { repository.store.update { it.copy(readerFit = if (fit == MangaReaderFit.PAGE) MangaReaderFit.WIDTH else MangaReaderFit.PAGE) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Reader setting could not be saved" }
                    runCatching { focus.requestFocus() }
                } }) { Text(if (fit == MangaReaderFit.PAGE) "Fit page" else "Fit width") }
                TextButton(onClick = { zoomBy(0.8f) }, modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
                TextButton(onClick = { zoomBy(1.25f) }, modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
            }
            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(Modifier.padding(24.dp)) {
                    Text(error.orEmpty(), color = Color.White)
                    TextButton(onClick = { retry++ }) { Text("Retry") }
                }
                else -> ReaderPages(repository, chapter, pages, saved?.page ?: 0, saved?.pageOffsetPx ?: 0, mode, fit, zoom, { current = it }, ::zoomBy, focus,
                    controls, hasNext, { controls = !controls }, onBack, onNext)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ColumnScope.ReaderPages(repository: MangaRepository, chapter: MangaChapter, pages: List<MangaPage>, initialPage: Int, initialOffset: Int,
    mode: MangaReaderMode, fit: MangaReaderFit, zoom: MutableMap<String, Float>, onCurrent: (String) -> Unit, zoomBy: (Float) -> Unit, focus: FocusRequester,
    controls: Boolean, hasNext: Boolean, toggleControls: () -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val initial = initialPage.coerceIn(pages.indices)
    val pager = rememberPagerState(initialPage = initial) { pages.size }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    var restorePending by remember { mutableStateOf(mode == MangaReaderMode.WEBTOON && initialOffset > 0) }
    var previousMode by remember { mutableStateOf(mode) }
    val scope = rememberCoroutineScope()
    val page = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) else pager.settledPage
    val readyImages = remember { mutableStateMapOf<Int, Boolean>() }
    SideEffect { pages.getOrNull(page)?.let { onCurrent(it.id) } }
    LaunchedEffect(page) {
        // Fetch the next two pages ahead of the reader; failures stay silent here and surface when the page itself is shown.
        kotlinx.coroutines.delay(150)
        for (index in page + 1..minOf(page + PREFETCH_PAGES, pages.lastIndex)) {
            try { repository.pageFile(pages[index], chapter.sourceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    LaunchedEffect(mode, readyImages[initial]) {
        if (restorePending && mode == MangaReaderMode.WEBTOON && readyImages[initial] == true) {
            // A loading placeholder cannot hold the offset of a long decoded image.
            withFrameNanos { }; withFrameNanos { }
            scroll.scrollToItem(initial, initialOffset.coerceIn(0, 10_000_000))
            restorePending = false
        }
    }
    val endVisible by remember { derivedStateOf {
        val visible = scroll.layoutInfo.visibleItemsInfo
        !scroll.canScrollForward && visible.any { it.index == pages.size } &&
            visible.filter { it.index in pages.indices }.all { readyImages[it.index] == true }
    } }
    var webtoonFinished by remember { mutableStateOf(false) }
    LaunchedEffect(mode, endVisible) {
        webtoonFinished = false
        if (mode == MangaReaderMode.WEBTOON && endVisible) {
            // Give decoded image aspect ratios two layout frames to replace their loading placeholders.
            withFrameNanos { }; withFrameNanos { }
            webtoonFinished = endVisible
        }
    }
    LaunchedEffect(mode) {
        if (mode != previousMode) {
            if (mode == MangaReaderMode.WEBTOON) scroll.scrollToItem(pager.currentPage)
            else if (previousMode == MangaReaderMode.WEBTOON) pager.scrollToPage(scroll.firstVisibleItemIndex)
            previousMode = mode
        }
    }
    var progressError by remember { mutableStateOf(false) }
    var progressRetry by remember { mutableIntStateOf(0) }
    suspend fun saveProgress(index: Int, offset: Int, finished: Boolean) {
        try {
            repository.store.progress(MangaProgress(chapter.bookId, chapter.id, index, pages.size, mode,
                reachedEnd = finished, pageOffsetPx = offset.coerceIn(0, 10_000_000)))
            progressError = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { progressError = true }
    }
    LaunchedEffect(mode, progressRetry) {
        fun current(): Triple<Int, Int, Boolean> {
            val index = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) else pager.settledPage
            val offset = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemScrollOffset else 0
            return Triple(index, offset, if (mode == MangaReaderMode.WEBTOON) webtoonFinished else index == pages.lastIndex)
        }
        try {
            snapshotFlow { if (restorePending) null else current() }.filterNotNull().distinctUntilChanged().debounce(200).collect { (index, offset, finished) ->
                if (!restorePending) saveProgress(index, offset, finished)
            }
        } finally {
            // Leaving a chapter flushes the last scroll position even inside the debounce interval.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val (index, offset, finished) = current()
                if (!restorePending) saveProgress(index, offset, finished)
            }
        }
    }
    fun go(target: Int) {
        restorePending = false
        val safe = target.coerceIn(pages.indices)
        scope.launch { if (mode == MangaReaderMode.WEBTOON) scroll.animateScrollToItem(safe) else pager.animateScrollToPage(safe) }
    }
    fun scrollScreen(direction: Int) {
        restorePending = false
        scope.launch { scroll.animateScrollBy(direction * (scroll.layoutInfo.viewportSize.height * .85f).coerceAtLeast(100f)) }
    }
    fun advance() {
        if (mode == MangaReaderMode.WEBTOON && !webtoonFinished) scrollScreen(1)
        else if (page == pages.lastIndex || mode == MangaReaderMode.WEBTOON && webtoonFinished) scope.launch {
            try { repository.store.markRead(chapter, true); onNext() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressError = true }
        }
        else go(page + 1)
    }
    Box(Modifier.weight(1f).fillMaxWidth().testTag("manga:reader").focusRequester(focus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.Escape -> { if (controls) onBack() else toggleControls(); true }
            Key.F11 -> { toggleControls(); true }
            Key.DirectionRight -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(1) else go(page + if (mode == MangaReaderMode.PAGES_RTL) -1 else 1); true }
            Key.DirectionLeft -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else go(page + if (mode == MangaReaderMode.PAGES_RTL) 1 else -1); true }
            Key.PageDown, Key.Spacebar -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(1) else go(page + 1); true }
            Key.PageUp -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else go(page - 1); true }
            Key.DirectionDown -> { if (mode == MangaReaderMode.WEBTOON) { scrollScreen(1); true } else false }
            Key.DirectionUp -> { if (mode == MangaReaderMode.WEBTOON) { scrollScreen(-1); true } else false }
            Key.Plus, Key.Equals, Key.NumPadAdd -> { zoomBy(1.25f); true }
            Key.Minus, Key.NumPadSubtract -> { zoomBy(0.8f); true }
            Key.Zero, Key.NumPad0 -> { pages.getOrNull(page)?.let { zoom.remove(it.id) }; true }
            else -> false
        }
    }.focusable()) {
        if (mode == MangaReaderMode.WEBTOON) LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("manga:webtoon")) {
            itemsIndexed(pages, key = { _, item -> item.id }) { index, item ->
                MangaImage(repository, item, chapter.sourceId, webtoon = true, fitWidth = true, zoom[item.id] ?: 1f, { zoom[item.id] = it }, toggleControls,
                    onReady = { ready -> readyImages[index] = ready })
            }
            item(key = "chapter-end") { Box(Modifier.fillMaxWidth().height(1.dp).testTag("manga:chapter-end")) }
        } else HorizontalPager(pager, reverseLayout = mode == MangaReaderMode.PAGES_RTL, beyondViewportPageCount = 0, modifier = Modifier.fillMaxSize()) { index ->
            val item = pages[index]
            MangaImage(repository, item, chapter.sourceId, webtoon = false, fitWidth = fit == MangaReaderFit.WIDTH, zoom[item.id] ?: 1f, { zoom[item.id] = it }, toggleControls)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    if (progressError) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Reading progress could not be saved.", color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = { progressRetry++ }) { Text("Retry save") }
    }
    if (controls) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else go(page - 1) },
            enabled = if (mode == MangaReaderMode.WEBTOON) scroll.canScrollBackward else page > 0) { Text("Previous") }
        Slider(value = page.toFloat(), onValueChange = { go(it.toInt()) }, enabled = pages.size > 1,
            valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
        Text("${page + 1} / ${pages.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = ::advance) {
            val finished = if (mode == MangaReaderMode.WEBTOON) webtoonFinished else page == pages.lastIndex
            Text(if (!finished) "Next" else if (hasNext) "Next chapter" else "Finish")
        }
    }
}

/** Pages fetched ahead of the reader: a small window, so a slow source never downloads a whole chapter just by being opened. */
private const val PREFETCH_PAGES = 2

@Composable
private fun MangaImage(repository: MangaRepository, page: MangaPage, sourceId: String, webtoon: Boolean, fitWidth: Boolean, scale: Float,
    onScale: (Float) -> Unit, onTap: () -> Unit, onReady: (Boolean) -> Unit = {}) {
    var file by remember(page.id) { mutableStateOf<File?>(null) }
    val latestReady = rememberUpdatedState(onReady)
    val latestScale = rememberUpdatedState(scale)
    DisposableEffect(page.id) { latestReady.value(false); onDispose { latestReady.value(false) } }
    var failure by remember(page.id) { mutableStateOf<String?>(null) }
    var retry by remember(page.id) { mutableIntStateOf(0) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    if (scale == 1f && offset != Offset.Zero) offset = Offset.Zero
    LaunchedEffect(page.id, retry) {
        failure = null
        try { file = repository.pageFile(page, sourceId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Page could not be loaded" }
    }
    val transforms = rememberTransformableState { zoomChange, pan, _ ->
        val next = (latestScale.value * zoomChange).coerceIn(1f, 5f)
        onScale(next)
        offset = if (next == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-2000f, 2000f), (offset.y + pan.y).coerceIn(-4000f, 4000f))
    }
    val tall = webtoon || fitWidth
    // Fit width in the paged modes scrolls the rest of a tall page vertically inside the page.
    val frame = if (webtoon) Modifier.fillMaxWidth().heightIn(min = 180.dp)
        else if (fitWidth) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
    Box(frame, contentAlignment = if (fitWidth && !webtoon) Alignment.TopCenter else Alignment.Center) {
        if (file != null) AsyncImage(
            model = file, contentDescription = "Manga page", contentScale = if (tall) ContentScale.FillWidth else ContentScale.Fit,
            modifier = (if (tall) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .semantics { stateDescription = "Zoom ${(scale * 100).roundToInt()}%" }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .transformable(transforms, canPan = { latestScale.value > 1f })
                .pointerInput(page.id, onTap) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onScale(if (latestScale.value == 1f) 2f else 1f); offset = Offset.Zero }) },
            onSuccess = { latestReady.value(true) },
            onError = { latestReady.value(false); failure = "This page could not be decoded" },
        )
        else if (failure == null) CircularProgressIndicator()
        failure?.let { message -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(message, color = Color.White); TextButton(onClick = { latestReady.value(false); file = null; retry++ }) { Text("Retry page") }
        } }
    }
}
