package com.cosmotune.manga.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The manga reader (DESIGN_SYSTEM.md 4.19, Mihon's chrome): the pages full screen on black, and over them, while shown, a top bar
 * (back, the series and the chapter, zoom, the reader settings) and a bottom bar (the page slider with "12 / 48", then the
 * previous page, the chapter list, the settings and the next page or chapter). The chrome is up when a chapter opens and goes
 * away as soon as a page is swiped; a tap on the middle third brings it back, the outer thirds turn the page (mirrored for
 * right-to-left). Pinch and double-tap zoom. Music keeps playing; the dock is hidden.
 */
@Composable
internal fun MangaReader(repository: MangaRepository, chapter: MangaChapter, seriesTitle: String, hasNext: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    key(chapter.id) {
        MangaBackHandler(true, onBack)
        var pages by remember { mutableStateOf(emptyList<MangaPage>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var verificationAddress by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var controls by rememberSaveable { mutableStateOf(true) }
        var settings by remember { mutableStateOf(false) }
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
        fun chooseFit(value: MangaReaderFit) {
            scope.launch {
                try { repository.store.update { it.copy(readerFit = value) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Reader setting could not be saved" }
                runCatching { focus.requestFocus() }
            }
        }
        val revision by repository.sourceChanges.collectAsState()
        LaunchedEffect(retry, revision) {
            loading = true; error = null
            verificationAddress = null
            try { pages = repository.pages(chapter, refresh = retry > 0); check(pages.isNotEmpty()) { "No pages are available for this chapter" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Chapter could not be loaded"; verificationAddress = (failure as? MangaBrowserRequired)?.address }
            finally { loading = false }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f)) }
                error != null -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp, start = 24.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(mangaText("This chapter could not be opened"), style = ReadType.titleLarge(), color = Color.White)
                    Text(error.orEmpty(), style = ReadType.body(), color = Color.White.copy(alpha = 0.72f))
                    KitButton(mangaText("Retry"), { retry++ }, kind = KitButtonKind.TONAL)
                    if (chapter.sourceId != "local") MangaVerificationControls(repository, chapter.sourceId, verificationAddress)
                }
                else -> ReaderPages(repository, chapter, pages, saved?.page ?: 0, saved?.pageOffsetPx ?: 0, mode, fit, zoom, { current = it }, ::zoomBy, focus,
                    controls, hasNext, { controls = !controls }, { controls = false }, { settings = true }, onBack, onNext)
            }
            if (controls || loading || error != null) ReaderTopBar(seriesTitle, chapter.title, onBack, onZoomOut = { zoomBy(0.8f) }, onZoomIn = { zoomBy(1.25f) },
                onSettings = { settings = true }, modifier = Modifier.align(Alignment.TopCenter))
        }
        if (settings) KitSheet(onDismiss = { settings = false; runCatching { focus.requestFocus() } }) {
            ReadSheetHeader(mangaText("Reading mode"), onDone = { settings = false; runCatching { focus.requestFocus() } })
            ReadSheetSection(mangaText("Direction")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaReaderMode.entries.forEach { item -> ReadChip(mangaText(readerModeLabel(item)), mode == item, { mode = item }) }
                }
            }
            if (mode != MangaReaderMode.WEBTOON) ReadSheetSection(mangaText("Scale")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadChip(mangaText("Fit page"), fit == MangaReaderFit.PAGE, { chooseFit(MangaReaderFit.PAGE) })
                    ReadChip(mangaText("Fit width"), fit == MangaReaderFit.WIDTH, { chooseFit(MangaReaderFit.WIDTH) })
                }
            }
            ReadSheetRow(mangaText(if (library.readerMode == mode) "This is the default for new series" else "Use ${readerModeLabel(mode).lowercase()} for new series"), CosmoIcons.Check,
                enabled = library.readerMode != mode, onClick = { scope.launch {
                    try { repository.store.update { it.copy(readerMode = mode) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Reader setting could not be saved" }
                } })
            Text(mangaText("The direction you choose is remembered with this chapter's reading position."), style = ReadType.meta(),
                color = Color(LocalCosmoColors.current.palette.textSecondary), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The reader's top bar: back, the series over the chapter, zoom out and in, the reader settings; `surface1` at 92 %. */
@Composable
private fun ReaderTopBar(series: String, chapter: String, onBack: () -> Unit, onZoomOut: () -> Unit, onZoomIn: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    Row(
        modifier.fillMaxWidth().background(Color(palette.surface).copy(alpha = 0.92f)).windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitIconButton(CosmoIcons.ArrowLeft, mangaText("Back"), onBack)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (series.isNotBlank()) Text(series, style = ReadType.rowTitle(), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chapter, style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KitIconButton(Icons.Rounded.ZoomOut, mangaText("Zoom out"), onZoomOut)
        KitIconButton(Icons.Rounded.ZoomIn, mangaText("Zoom in"), onZoomIn)
        KitIconButton(Icons.Rounded.Tune, mangaText("Reader settings"), onSettings, Modifier.testTag("manga:reader:settings"))
    }
}

@OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun BoxScope.ReaderPages(repository: MangaRepository, chapter: MangaChapter, pages: List<MangaPage>, initialPage: Int, initialOffset: Int,
    mode: MangaReaderMode, fit: MangaReaderFit, zoom: MutableMap<String, Float>, onCurrent: (String) -> Unit, zoomBy: (Float) -> Unit, focus: FocusRequester,
    controls: Boolean, hasNext: Boolean, toggleControls: () -> Unit, hideControls: () -> Unit, openSettings: () -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val initial = initialPage.coerceIn(pages.indices)
    val pager = rememberPagerState(initialPage = initial) { pages.size }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    var restorePending by remember { mutableStateOf(mode == MangaReaderMode.WEBTOON && initialOffset > 0) }
    var previousMode by remember { mutableStateOf(mode) }
    val scope = rememberCoroutineScope()
    val page = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) else pager.settledPage
    val readyImages = remember { mutableStateMapOf<Int, Boolean>() }
    SideEffect { pages.getOrNull(page)?.let { onCurrent(it.id) } }
    // A swipe or a drag means reading has started: the chrome steps aside (a tap on the middle brings it back).
    val pagerDragged by pager.interactionSource.collectIsDraggedAsState()
    val listDragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pagerDragged || listDragged) { if (pagerDragged || listDragged) hideControls() }
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
    val finished = if (mode == MangaReaderMode.WEBTOON) webtoonFinished else page == pages.lastIndex
    fun advance() {
        if (mode == MangaReaderMode.WEBTOON && !webtoonFinished) scrollScreen(1)
        else if (page == pages.lastIndex || mode == MangaReaderMode.WEBTOON && webtoonFinished) scope.launch {
            try { repository.store.markRead(chapter, true); onNext() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressError = true }
        }
        else go(page + 1)
    }
    fun back() { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else go(page - 1) }
    val onTap: (Float, Float) -> Unit = { x, width ->
        when (readerTap(x, width, mode)) {
            ReaderTap.MENU -> toggleControls()
            ReaderTap.NEXT -> { hideControls(); advance() }
            ReaderTap.PREVIOUS -> { hideControls(); back() }
        }
    }
    Box(Modifier.fillMaxSize().testTag("manga:reader").focusRequester(focus).onPreviewKeyEvent { event ->
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
                MangaImage(repository, item, chapter.sourceId, webtoon = true, fitWidth = true, zoom[item.id] ?: 1f, { zoom[item.id] = it }, onTap,
                    onReady = { ready -> readyImages[index] = ready })
            }
            item(key = "chapter-end") { Box(Modifier.fillMaxWidth().height(1.dp).testTag("manga:chapter-end")) }
        } else HorizontalPager(pager, reverseLayout = mode == MangaReaderMode.PAGES_RTL, beyondViewportPageCount = 0, modifier = Modifier.fillMaxSize()) { index ->
            val item = pages[index]
            MangaImage(repository, item, chapter.sourceId, webtoon = false, fitWidth = fit == MangaReaderFit.WIDTH, zoom[item.id] ?: 1f, { zoom[item.id] = it }, onTap)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val palette = LocalCosmoColors.current.palette
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        if (progressError) Row(Modifier.fillMaxWidth().background(Color(palette.surface).copy(alpha = 0.92f)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mangaText("Reading progress could not be saved."), style = ReadType.body(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
            KitTextButton(mangaText("Retry save"), { progressRetry++ })
        }
        if (controls) Column(Modifier.fillMaxWidth().background(Color(palette.surface).copy(alpha = 0.92f)).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(value = page.toFloat(), onValueChange = { go(it.roundToInt()) }, enabled = pages.size > 1,
                    valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(palette.accent), activeTrackColor = Color(palette.accent), inactiveTrackColor = Color(palette.textPrimary).copy(alpha = 0.16f)))
                Spacer(Modifier.width(12.dp))
                Text("${page + 1} / ${pages.size}", style = ReadType.meta().copy(fontFeatureSettings = "tnum"), color = Color(palette.textPrimary))
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                KitIconButton(Icons.Rounded.SkipPrevious, mangaText("Previous"), ::back)
                KitIconButton(CosmoIcons.List, mangaText("Chapters"), onBack)
                KitIconButton(Icons.Rounded.Tune, mangaText("Reading mode"), openSettings)
                KitIconButton(if (finished) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.SkipNext,
                    mangaText(if (!finished) "Next" else if (hasNext) "Next chapter" else "Finish"), ::advance)
            }
        }
    }
}

/** Pages fetched ahead of the reader: a small window, so a slow source never downloads a whole chapter just by being opened. */
private const val PREFETCH_PAGES = 2

@Composable
private fun MangaImage(repository: MangaRepository, page: MangaPage, sourceId: String, webtoon: Boolean, fitWidth: Boolean, scale: Float,
    onScale: (Float) -> Unit, onTap: (Float, Float) -> Unit, onReady: (Boolean) -> Unit = {}) {
    var file by remember(page.id) { mutableStateOf<File?>(null) }
    val latestReady = rememberUpdatedState(onReady)
    val latestScale = rememberUpdatedState(scale)
    val latestTap = rememberUpdatedState(onTap)
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
                .pointerInput(page.id) {
                    detectTapGestures(
                        onTap = { position -> latestTap.value(position.x, size.width.toFloat()) },
                        onDoubleTap = { onScale(if (latestScale.value == 1f) 2f else 1f); offset = Offset.Zero },
                    )
                },
            onSuccess = { latestReady.value(true) },
            onError = { latestReady.value(false); failure = "This page could not be decoded" },
        )
        else if (failure == null) CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
        failure?.let { message -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(message, style = ReadType.body(), color = Color.White)
            KitTextButton(mangaText("Retry page"), { latestReady.value(false); file = null; retry++ })
        } }
    }
}
