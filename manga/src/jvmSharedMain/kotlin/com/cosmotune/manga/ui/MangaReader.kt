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
import androidx.compose.material.icons.rounded.ChromeReaderMode
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.OrbitReaderCard
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitLook
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The manga reader (board D): the pages full screen on black with nothing over them until a tap on the middle third, which brings
 * up the slim card at the foot in the series' colour: back, the mono "CHAPTER 12 · PAGE 6 OF 22", the page line with its moon
 * (tap or drag to go to a page), and the round buttons (previous, next page or chapter, zoom out and in, the reading mode, the
 * chapters). A swipe puts the card away again; the outer thirds turn the page (mirrored for right-to-left). Pinch and double-tap
 * zoom. Music keeps playing.
 */
@Composable
internal fun MangaReader(repository: MangaRepository, chapter: MangaChapter, book: MangaBook?, hasNext: Boolean, onBack: () -> Unit, onNext: () -> Unit,
    onChapter: (MangaChapter) -> Unit = {}) {
    val reading by repository.store.state.collectAsState()
    ReaderKeepScreenOn(reading.readerKeepScreenOn)
    key(chapter.id) {
        MangaBackHandler(true, onBack)
        var pages by remember { mutableStateOf(emptyList<MangaPage>()) }
        var error by remember { mutableStateOf<String?>(null) }
        var verificationAddress by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var controls by rememberSaveable { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }
        var readerSettings by remember { mutableStateOf(false) }
        var chapters by remember { mutableStateOf(false) }
        val rotation = rememberReaderRotation()
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
        Box(Modifier.fillMaxSize().background(readerGround(reading.readerBackground))) {
            when {
                // The chapter's name shows at once while its pages come, with the way back in the corner.
                loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    Text(readerLabel(chapter, 0, 0).substringBefore(" · "), style = ReadType.meta(), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 14.dp))
                }
                // The problem stands on a card of the page's own ground, so it reads in both palettes over the black.
                error != null -> Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp, start = 16.dp, end = 16.dp).widthIn(max = 560.dp)
                    .clip(ReadShape.md).background(orbitLook().ground).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(mangaText("This chapter could not be opened"), style = ReadType.titleLarge(), color = orbitLook().ink)
                    Text(error.orEmpty(), style = ReadType.body(), color = orbitLook().dim)
                    ReadButton(mangaText("Retry"), { retry++ })
                    if (chapter.sourceId != "local") MangaVerificationControls(repository, chapter.sourceId, verificationAddress)
                }
                else -> ReaderPages(repository, chapter, book, pages, saved?.page ?: 0, saved?.pageOffsetPx ?: 0, mode, fit, zoom, { current = it }, ::zoomBy, focus,
                    controls, hasNext, { controls = !controls }, { controls = false }, ReaderActions({ settings = true }, { readerSettings = true }, { chapters = true }, rotation),
                    onBack, onNext)
            }
            // While the chapter loads or fails there are no pages to tap, so the way back stays in the corner.
            if (loading || error != null) WatchRoundButton(CosmoIcons.ChevronLeft, mangaText("Back"), orbitLook(), onBack, ink = Color.White,
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp))
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
                color = orbitLook().dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
        if (readerSettings) KitSheet(onDismiss = { readerSettings = false; runCatching { focus.requestFocus() } }) {
            ReadSheetHeader(mangaText("Reader settings"), onDone = { readerSettings = false; runCatching { focus.requestFocus() } })
            fun change(block: (MangaLibraryState) -> MangaLibraryState) = scope.launch {
                try { repository.store.update(block) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Reader setting could not be saved" }
            }
            ReadSheetSection(mangaText("Brightness") + " · ${(library.readerBrightness * 100).roundToInt()}%") {
                ReadSlider(library.readerBrightness, { value -> change { it.copy(readerBrightness = value.coerceIn(0.3f, 1f)) } }, 0.3f..1f, 6, "Brightness",
                    Modifier.testTag("manga:reader:brightness"))
            }
            MangaReaderImageOptions(library, Modifier.padding(horizontal = 20.dp)) { block -> change(block) }
            com.cosmotune.ui.orbit.OrbitSwitchRow(mangaText("Keep screen on while reading"), library.readerKeepScreenOn, { on -> change { it.copy(readerKeepScreenOn = on) } },
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            com.cosmotune.ui.orbit.OrbitSwitchRow(mangaText("Page number at the foot"), library.readerPageNumber, { on -> change { it.copy(readerPageNumber = on) } },
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), detail = mangaText("While the reader's card is hidden"))
            Spacer(Modifier.height(16.dp))
        }
        if (chapters && book != null) KitSheet(onDismiss = { chapters = false; runCatching { focus.requestFocus() } }) {
            ReadSheetHeader(book.title, onDone = { chapters = false }, doneLabel = "Close")
            val listed = book.listedChapters(chapter.edition)
            val list = rememberLazyListState(initialFirstVisibleItemIndex = listed.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("manga:reader:chapters"), state = list) {
                itemsIndexed(listed, key = { _, item -> item.id }) { index, item ->
                    val read = item.id in library.readChapters
                    ReadChapterRow(item.title, if (item.id == chapter.id) mangaText("Reading now") else chapterSubtitle(item, library.progress[item.bookId], read),
                        read = read, fresh = item.id in library.newChapters, download = ReadDownload.NONE,
                        onClick = { chapters = false; if (item.id != chapter.id) onChapter(item) }, onLongClick = {}, onDownload = {},
                        modifier = Modifier.padding(horizontal = 16.dp), next = item.id == chapter.id, number = chapterNumber(item, index + 1))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** What the reader's card opens: the reading mode, the reader's settings, the chapters, and the orientation lock where there is one. */
internal class ReaderActions(val mode: () -> Unit, val settings: () -> Unit, val chapters: () -> Unit, val rotation: ReaderRotation?)

@OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun BoxScope.ReaderPages(repository: MangaRepository, chapter: MangaChapter, book: MangaBook?, pages: List<MangaPage>, initialPage: Int, initialOffset: Int,
    mode: MangaReaderMode, fit: MangaReaderFit, zoom: MutableMap<String, Float>, onCurrent: (String) -> Unit, zoomBy: (Float) -> Unit, focus: FocusRequester,
    controls: Boolean, hasNext: Boolean, toggleControls: () -> Unit, hideControls: () -> Unit, actions: ReaderActions, onBack: () -> Unit, onNext: () -> Unit) {
    val library by repository.store.state.collectAsState()
    val initial = initialPage.coerceIn(pages.indices)
    // Two pages side by side only in the paged modes and only while the window is wider than tall.
    var wide by remember { mutableStateOf(false) }
    val spreads = remember(pages.size, library.readerSpread, library.readerCoverAlone, wide, mode) {
        if (library.readerSpread && wide && mode != MangaReaderMode.WEBTOON) mangaSpreads(pages.size, library.readerCoverAlone) else pages.indices.map { listOf(it) }
    }
    fun spreadOf(index: Int): Int = spreads.indexOfFirst { index in it }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = spreadOf(initial)) { spreads.size }
    // When the spreads change (the window turned, the setting changed) the reader stays on the same page.
    var shownPage by remember { mutableIntStateOf(initial) }
    LaunchedEffect(spreads) { val target = spreadOf(shownPage); if (target != pager.currentPage) pager.scrollToPage(target) }
    val treatment = MangaPageTreatment(library.readerCrop, library.readerRotateWide && !(library.readerSpread && wide))
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    var restorePending by remember { mutableStateOf(mode == MangaReaderMode.WEBTOON && initialOffset > 0) }
    var previousMode by remember { mutableStateOf(mode) }
    val scope = rememberCoroutineScope()
    val page = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) else spreads.getOrNull(pager.settledPage)?.first() ?: 0
    SideEffect { shownPage = page }
    val lastShown = mode != MangaReaderMode.WEBTOON && pager.settledPage >= spreads.lastIndex
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
            if (mode == MangaReaderMode.WEBTOON) scroll.scrollToItem(spreads.getOrNull(pager.currentPage)?.first() ?: 0)
            else if (previousMode == MangaReaderMode.WEBTOON) pager.scrollToPage(spreadOf(scroll.firstVisibleItemIndex))
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
            val spread = spreads.getOrNull(pager.settledPage)
            val index = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) else spread?.first() ?: 0
            val offset = if (mode == MangaReaderMode.WEBTOON) scroll.firstVisibleItemScrollOffset else 0
            return Triple(index, offset, if (mode == MangaReaderMode.WEBTOON) webtoonFinished else spread?.contains(pages.lastIndex) == true)
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
        scope.launch { if (mode == MangaReaderMode.WEBTOON) scroll.animateScrollToItem(safe) else pager.animateScrollToPage(spreadOf(safe)) }
    }
    /** One page (or spread) on, [by] +1, or back, -1, in the paged modes. */
    fun step(by: Int) {
        restorePending = false
        val target = (pager.settledPage + by).coerceIn(spreads.indices)
        scope.launch { pager.animateScrollToPage(target) }
    }
    fun scrollScreen(direction: Int) {
        restorePending = false
        scope.launch { scroll.animateScrollBy(direction * (scroll.layoutInfo.viewportSize.height * .85f).coerceAtLeast(100f)) }
    }
    val finished = if (mode == MangaReaderMode.WEBTOON) webtoonFinished else lastShown
    fun advance() {
        if (mode == MangaReaderMode.WEBTOON && !webtoonFinished) scrollScreen(1)
        else if (lastShown || mode == MangaReaderMode.WEBTOON && webtoonFinished) scope.launch {
            try { if (!library.incognito) repository.store.markRead(chapter, true); onNext() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressError = true }
        }
        else step(1)
    }
    fun back() { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else step(-1) }
    val onTap: (Float, Float, Float, Float) -> Unit = { x, y, width, height ->
        when (readerTap(x, y, width, height, mode, library.readerTapZones, library.readerTapInvert)) {
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
            Key.DirectionRight -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(1) else step(if (mode == MangaReaderMode.PAGES_RTL) -1 else 1); true }
            Key.DirectionLeft -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else step(if (mode == MangaReaderMode.PAGES_RTL) 1 else -1); true }
            Key.PageDown, Key.Spacebar -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(1) else step(1); true }
            Key.PageUp -> { if (mode == MangaReaderMode.WEBTOON) scrollScreen(-1) else step(-1); true }
            // Volume keys turn pages where the device has them, when that is on in the reader's settings.
            Key.VolumeDown -> if (!library.readerVolumeKeys) false else { advance(); true }
            Key.VolumeUp -> if (!library.readerVolumeKeys) false else { back(); true }
            Key.DirectionDown -> { if (mode == MangaReaderMode.WEBTOON) { scrollScreen(1); true } else false }
            Key.DirectionUp -> { if (mode == MangaReaderMode.WEBTOON) { scrollScreen(-1); true } else false }
            Key.Plus, Key.Equals, Key.NumPadAdd -> { zoomBy(1.25f); true }
            Key.Minus, Key.NumPadSubtract -> { zoomBy(0.8f); true }
            Key.Zero, Key.NumPad0 -> { pages.getOrNull(page)?.let { zoom.remove(it.id) }; true }
            else -> false
        }
    }.focusable().onSizeChanged { wide = it.width > it.height }) {
        val filter = readerFilter(library.readerFilter)
        if (mode == MangaReaderMode.WEBTOON) LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("manga:webtoon")) {
            itemsIndexed(pages, key = { _, item -> item.id }) { index, item ->
                MangaImage(repository, item, chapter.sourceId, webtoon = true, fitWidth = true, zoom[item.id] ?: 1f, { zoom[item.id] = it }, onTap,
                    onReady = { ready -> readyImages[index] = ready }, treatment = treatment.copy(rotateWide = false), filter = filter)
            }
            item(key = "chapter-end") { Box(Modifier.fillMaxWidth().height(1.dp).testTag("manga:chapter-end")) }
        } else HorizontalPager(pager, reverseLayout = mode == MangaReaderMode.PAGES_RTL, beyondViewportPageCount = 0, modifier = Modifier.fillMaxSize()) { index ->
            val spread = spreads[index]
            if (spread.size == 1) {
                val item = pages[spread.single()]
                MangaImage(repository, item, chapter.sourceId, webtoon = false, fitWidth = fit == MangaReaderFit.WIDTH, zoom[item.id] ?: 1f, { zoom[item.id] = it }, onTap,
                    treatment = treatment, filter = filter)
            } else Row(Modifier.fillMaxSize().testTag("manga:spread")) {
                // Right to left reads the right-hand page first.
                (if (mode == MangaReaderMode.PAGES_RTL) spread.reversed() else spread).forEachIndexed { side, pageIndex ->
                    val item = pages[pageIndex]
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = if (side == 0) Alignment.CenterEnd else Alignment.CenterStart) {
                        MangaImage(repository, item, chapter.sourceId, webtoon = false, fitWidth = false, zoom[item.id] ?: 1f, { zoom[item.id] = it },
                            { x, y, width, height -> onTap(x + side * width, y, width * 2, height) }, treatment = treatment, filter = filter)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Brightness is a dimming over the pages (the system's own stays as it is); it never takes a tap.
    if (library.readerBrightness < 1f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - library.readerBrightness).coerceIn(0f, 0.7f))))
    // With the card hidden, a faint "6 / 22" at the foot says where the reader is (Mihon's page number).
    if (!controls && library.readerPageNumber && pages.size > 1) Text("${page + 1} / ${pages.size}", style = ReadType.caption(), color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 6.dp).testTag("manga:reader:page"))
    val (fill, ink) = coverFill(book?.colourUrl(), book?.id ?: chapter.bookId)
    BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
    // Zoom buttons only where there is no pinch to do it (a wide window with a mouse); a phone gets the orientation lock instead.
    val pointer = maxWidth >= 840.dp
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 620.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progressError) ReadInlineBanner(mangaText("Reading progress could not be saved."), Modifier.background(orbitLook().ground, ReadShape.md),
            actionLabel = mangaText("Retry save"), onAction = { progressRetry++ })
        if (controls) OrbitReaderCard(
            label = readerLabel(chapter, page, pages.size),
            progress = { if (pages.size <= 1) 1f else page / (pages.size - 1f) },
            fill = fill, ink = ink,
            onSeek = if (pages.size > 1) ({ fraction -> go((fraction * (pages.size - 1)).roundToInt()) }) else null,
            leading = { OrbitReaderButton(CosmoIcons.ChevronLeft, mangaText("Back"), ink, fill, onBack) },
            modifier = Modifier.testTag("manga:reader:card"),
        ) {
            OrbitReaderButton(Icons.Rounded.SkipPrevious, mangaText("Previous"), ink, fill, ::back)
            OrbitReaderButton(if (finished) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.SkipNext,
                mangaText(if (!finished) "Next" else if (hasNext) "Next chapter" else "Finish"), ink, fill, ::advance)
            Spacer(Modifier.weight(1f))
            OrbitReaderButton(CosmoIcons.List, mangaText("Chapters"), ink, fill, actions.chapters, Modifier.testTag("manga:reader:chapters-button"))
            OrbitReaderButton(Icons.Rounded.ChromeReaderMode, mangaText("Reading mode"), ink, fill, actions.mode, Modifier.testTag("manga:reader:mode"))
            if (pointer) {
                OrbitReaderButton(Icons.Rounded.ZoomOut, mangaText("Zoom out"), ink, fill, { zoomBy(0.8f) })
                OrbitReaderButton(Icons.Rounded.ZoomIn, mangaText("Zoom in"), ink, fill, { zoomBy(1.25f) })
            }
            actions.rotation?.let { rotation ->
                OrbitReaderButton(Icons.Rounded.ScreenLockRotation, mangaText(if (rotation.locked) "Let the screen turn" else "Hold this orientation"), ink, fill, rotation.toggle,
                    Modifier.testTag("manga:reader:rotate"), on = rotation.locked)
            }
            OrbitReaderButton(Icons.Rounded.Tune, mangaText("Reader settings"), ink, fill, actions.settings, Modifier.testTag("manga:reader:settings"))
        }
    }
    }
}

/** "Chapter 12 · page 6 of 22" (the chapter's title when it has no number). */
internal fun readerLabel(chapter: MangaChapter, page: Int, pages: Int): String {
    val name = chapterName(chapter).takeIf { it != "reading" }?.replaceFirstChar(Char::uppercase) ?: chapter.title
    return "$name · page ${page + 1} of $pages"
}

/** Pages fetched ahead of the reader: a small window, so a slow source never downloads a whole chapter just by being opened. */
private const val PREFETCH_PAGES = 2

/** The ground around the pages. */
internal fun readerGround(background: MangaReaderBackground): Color = when (background) {
    MangaReaderBackground.BLACK -> Color.Black
    MangaReaderBackground.GREY -> Color(0xFF3A3A3A)
    MangaReaderBackground.WHITE -> Color.White
}

/** The colour filter over the pages (Mihon's grayscale and invert, and a warm sepia for night reading). */
internal fun readerFilter(filter: MangaReaderFilter): androidx.compose.ui.graphics.ColorFilter? = when (filter) {
    MangaReaderFilter.NONE -> null
    MangaReaderFilter.GREY -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) })
    MangaReaderFilter.INVERT -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    MangaReaderFilter.SEPIA -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
}

@Composable
private fun MangaImage(repository: MangaRepository, page: MangaPage, sourceId: String, webtoon: Boolean, fitWidth: Boolean, scale: Float,
    onScale: (Float) -> Unit, onTap: (Float, Float, Float, Float) -> Unit, onReady: (Boolean) -> Unit = {}, treatment: MangaPageTreatment = MangaPageTreatment(),
    filter: androidx.compose.ui.graphics.ColorFilter? = null) {
    var file by remember(page.id) { mutableStateOf<File?>(null) }
    val latestReady = rememberUpdatedState(onReady)
    val latestScale = rememberUpdatedState(scale)
    val latestTap = rememberUpdatedState(onTap)
    DisposableEffect(page.id) { latestReady.value(false); onDispose { latestReady.value(false) } }
    var failure by remember(page.id) { mutableStateOf<String?>(null) }
    var retry by remember(page.id) { mutableIntStateOf(0) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    if (scale == 1f && offset != Offset.Zero) offset = Offset.Zero
    LaunchedEffect(page.id, retry, treatment) {
        failure = null
        try { file = repository.pageFile(page, sourceId).let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mangaTreatedPage(it, treatment) } } }
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
            model = file, contentDescription = "Manga page", contentScale = if (tall) ContentScale.FillWidth else ContentScale.Fit, colorFilter = filter,
            modifier = (if (tall) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .semantics { stateDescription = "Zoom ${(scale * 100).roundToInt()}%" }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .transformable(transforms, canPan = { latestScale.value > 1f })
                .pointerInput(page.id) {
                    detectTapGestures(
                        onTap = { position -> latestTap.value(position.x, position.y, size.width.toFloat(), size.height.toFloat()) },
                        onDoubleTap = { onScale(if (latestScale.value == 1f) 2f else 1f); offset = Offset.Zero },
                    )
                },
            onSuccess = { latestReady.value(true) },
            onError = { latestReady.value(false); failure = "This page could not be decoded" },
        )
        else if (failure == null) CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
        failure?.let { message -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(message, style = ReadType.body(), color = Color.White)
            ReadTextButton(mangaText("Retry page"), { latestReady.value(false); file = null; retry++ })
        } }
    }
}
