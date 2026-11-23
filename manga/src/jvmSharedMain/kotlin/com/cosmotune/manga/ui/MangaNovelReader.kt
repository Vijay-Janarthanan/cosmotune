@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.OrbitReaderCard
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitLook
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The page and the words of a text chapter in each of the reader's colours; AUTO follows Cosmotune's own look. */
@Composable
internal fun novelColours(theme: MangaNovelStyle.Theme): Pair<Color, Color> = when (theme) {
    MangaNovelStyle.Theme.AUTO -> orbitLook().let { it.ground to it.ink }
    MangaNovelStyle.Theme.LIGHT -> Color(0xFFFBFAF7) to Color(0xFF1D1B18)
    MangaNovelStyle.Theme.SEPIA -> Color(0xFFF3E9D2) to Color(0xFF3E3022)
    MangaNovelStyle.Theme.DARK -> Color(0xFF1E1D1B) to Color(0xFFE4E0D8)
    MangaNovelStyle.Theme.BLACK -> Color.Black to Color(0xFFCBC6BD)
}

internal fun novelFontName(font: MangaNovelStyle.Font) = when (font) { MangaNovelStyle.Font.SERIF -> "Serif"; MangaNovelStyle.Font.SANS -> "Sans"; MangaNovelStyle.Font.MONO -> "Mono" }
internal fun novelThemeName(theme: MangaNovelStyle.Theme) = when (theme) {
    MangaNovelStyle.Theme.AUTO -> "Auto"; MangaNovelStyle.Theme.LIGHT -> "Light"; MangaNovelStyle.Theme.SEPIA -> "Sepia"
    MangaNovelStyle.Theme.DARK -> "Dark"; MangaNovelStyle.Theme.BLACK -> "Black"
}

/**
 * The reader for text chapters (web novels, public-domain novels): the words laid out in the listener's font, size, line spacing,
 * margins and colours, as pages to turn or one scrolling column, keeping the place to the character whichever is chosen. A tap
 * on the middle brings up the same card at the foot as the manga reader; the sides turn the page.
 */
@Composable
internal fun MangaNovelReader(repository: MangaRepository, chapter: MangaChapter, book: MangaBook?, hasNext: Boolean, onBack: () -> Unit, onNext: () -> Unit,
    onChapter: (MangaChapter) -> Unit = {}) {
    val library by repository.store.state.collectAsState()
    ReaderKeepScreenOn(library.readerKeepScreenOn)
    LaunchedEffect(Unit) {
        var since = System.currentTimeMillis()
        try {
            while (true) { kotlinx.coroutines.delay(60_000); val now = System.currentTimeMillis(); runCatching { repository.store.addReadingTime(now - since) }; since = now }
        } finally {
            val left = System.currentTimeMillis() - since
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { repository.store.addReadingTime(left) } }
        }
    }
    key(chapter.id) {
        MangaBackHandler(true, onBack)
        var text by remember { mutableStateOf<MangaNovelChapter?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var verificationAddress by remember { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        val revision by repository.sourceChanges.collectAsState()
        LaunchedEffect(retry, revision) {
            error = null; verificationAddress = null
            try { text = repository.chapterText(chapter, refresh = retry > 0).also { check(it.blocks.isNotEmpty()) { "This chapter has no text" } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Chapter could not be loaded"; verificationAddress = (failure as? MangaBrowserRequired)?.address }
        }
        val style = library.novelStyle.clamped()
        val (ground, ink) = novelColours(style.theme)
        Box(Modifier.fillMaxSize().background(ground).testTag("manga:novel")) {
            val loaded = text
            when {
                error != null -> Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp, start = 16.dp, end = 16.dp).widthIn(max = 560.dp)
                    .clip(ReadShape.md).background(orbitLook().ground).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(mangaText("This chapter could not be opened"), style = ReadType.titleLarge(), color = orbitLook().ink)
                    Text(error.orEmpty(), style = ReadType.body(), color = orbitLook().dim)
                    ReadButton(mangaText("Retry"), { retry++ })
                    MangaVerificationControls(repository, chapter.sourceId, verificationAddress)
                }
                loaded == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = ink.copy(alpha = 0.6f))
                    Text(chapter.title, style = ReadType.meta(), color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(top = 14.dp))
                }
                else -> NovelBody(repository, chapter, book, loaded, style, ink, hasNext, onBack, onNext, onChapter)
            }
            if (text == null || error != null) WatchRoundButton(CosmoIcons.ChevronLeft, mangaText("Back"), orbitLook(), onBack, ink = if (error != null) orbitLook().ink else ink,
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp))
        }
    }
}

/** Where the open chapter is asked to go: [place], or a page or screen on ([by] +1) or back (-1); [nonce] makes each ask new. */
private data class NovelMove(val place: MangaNovelPosition? = null, val by: Int = 0, val nonce: Long = 0)

@Composable
private fun BoxScope.NovelBody(repository: MangaRepository, chapter: MangaChapter, book: MangaBook?, text: MangaNovelChapter, style: MangaNovelStyle, ink: Color,
    hasNext: Boolean, onBack: () -> Unit, onNext: () -> Unit, onChapter: (MangaChapter) -> Unit) {
    val library by repository.store.state.collectAsState()
    val saved = remember { repository.store.state.value.progress[chapter.bookId]?.takeIf { it.chapterId == chapter.id } }
    var place by remember { mutableStateOf(MangaNovelLayout.position(saved, text)) }
    var finished by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(NovelMove()) }
    var progressError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun go(by: Int) { move = NovelMove(by = by, nonce = System.nanoTime()) }
    fun jump(to: MangaNovelPosition) { move = NovelMove(place = to, nonce = System.nanoTime()) }
    suspend fun save() {
        try { repository.store.progress(MangaNovelLayout.progress(chapter.bookId, chapter.id, text, place, finished), keepMode = true); progressError = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { progressError = true }
    }
    LaunchedEffect(Unit) {
        try { snapshotFlow { place to finished }.distinctUntilChanged().debounce(300).collect { save() } }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { save() } }
    }
    fun advance() {
        if (!finished) { go(1); return }
        scope.launch {
            try { if (!library.incognito) repository.store.markRead(chapter, true); onNext() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressError = true }
        }
    }
    val family = when (style.font) { MangaNovelStyle.Font.SERIF -> FontFamily.Serif; MangaNovelStyle.Font.SANS -> FontFamily.SansSerif; MangaNovelStyle.Font.MONO -> FontFamily.Monospace }
    val body = TextStyle(fontFamily = family, fontSize = style.size.sp, lineHeight = (style.size * style.lineSpacing).sp, color = ink)
    val heading = body.copy(fontSize = (style.size * 1.3f).sp, lineHeight = (style.size * 1.3f * 1.25f).sp, fontWeight = FontWeight.SemiBold)
    val gap = (style.size * style.lineSpacing * 0.6f).dp
    val onTap: (Float, Float) -> Unit = { x, width ->
        when {
            x < width / 3f -> { controls = false; go(-1) }
            x > width * 2f / 3f -> { controls = false; advance() }
            else -> controls = !controls
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).focusRequester(focus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.Escape -> { if (controls) onBack() else controls = true; true }
            Key.DirectionRight, Key.PageDown, Key.Spacebar -> { advance(); true }
            Key.DirectionLeft, Key.PageUp -> { go(-1); true }
            Key.DirectionDown -> if (style.paged) { advance(); true } else false
            Key.DirectionUp -> if (style.paged) { go(-1); true } else false
            Key.VolumeDown -> if (!library.readerVolumeKeys) false else { advance(); true }
            Key.VolumeUp -> if (!library.readerVolumeKeys) false else { go(-1); true }
            else -> false
        }
    }.focusable()) {
        val side = (style.margin + 16).dp
        val column = minOf(maxWidth - side * 2, 720.dp).coerceAtLeast(120.dp)
        if (style.paged) NovelPages(text, column, maxHeight, body, heading, gap, ink, place, move, controls, onTap,
            onPlace = { at, last -> place = at; finished = last })
        else NovelScroll(text, column, body, heading, gap, ink, place, move, hasNext, onTap,
            onPlace = { at, last -> place = at; finished = last }, onNext = ::advance)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val (fill, cardInk) = coverFill(book?.colourUrl(), book?.id ?: chapter.bookId)
    val fraction = MangaNovelLayout.fraction(text, place)
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 620.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progressError) ReadInlineBanner(mangaText("Reading progress could not be saved."), Modifier.background(orbitLook().ground, ReadShape.md),
            actionLabel = mangaText("Retry save"), onAction = { scope.launch { save() } })
        if (controls) OrbitReaderCard(
            label = chapterName(chapter).takeIf { it != "reading" }.orEmpty().ifEmpty { chapter.title } + " · ${(fraction * 100).roundToInt()}%",
            progress = { if (finished) 1f else fraction },
            fill = fill, ink = cardInk,
            onSeek = { value -> jump(MangaNovelLayout.at(text, value)) },
            leading = { NovelButton(CosmoIcons.ChevronLeft, "Back", cardInk, fill, onBack) },
            modifier = Modifier.testTag("manga:novel:card"),
        ) {
            NovelButton(Icons.Rounded.SkipPrevious, if (style.paged) "Previous page" else "Up a screen", cardInk, fill, { go(-1) })
            NovelButton(if (finished) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.SkipNext,
                if (!finished) (if (style.paged) "Next page" else "Down a screen") else if (hasNext) "Next chapter" else "Finish", cardInk, fill, ::advance)
            Spacer(Modifier.weight(1f))
            if (book != null) NovelButton(CosmoIcons.List, "Chapters", cardInk, fill, { chapters = true }, Modifier.testTag("manga:novel:chapters-button"))
            NovelButton(Icons.Rounded.FormatSize, "Text settings", cardInk, fill, { settings = true }, Modifier.testTag("manga:novel:settings"))
        }
    }
    if (settings) KitSheet(onDismiss = { settings = false; runCatching { focus.requestFocus() } }) {
        ReadSheetHeader(mangaText("Text"), onDone = { settings = false; runCatching { focus.requestFocus() } })
        MangaNovelStyleOptions(library.novelStyle) { change -> scope.launch {
            try { repository.store.update { it.copy(novelStyle = change(it.novelStyle).clamped()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressError = true }
        } }
        Spacer(Modifier.height(16.dp))
    }
    if (chapters && book != null) KitSheet(onDismiss = { chapters = false; runCatching { focus.requestFocus() } }) {
        ReadSheetHeader(book.title, onDone = { chapters = false }, doneLabel = "Close")
        val listed = book.listedChapters(chapter.edition)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = listed.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("manga:novel:chapters"), state = list) {
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

/** The text settings: font, size, line spacing, margins, colours, and pages or scrolling. Shared by the reader's sheet and Settings › Manga. */
@Composable
internal fun MangaNovelStyleOptions(style: MangaNovelStyle, modifier: Modifier = Modifier, change: ((MangaNovelStyle) -> MangaNovelStyle) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        ReadSheetSection(mangaText("Turn")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadChip(mangaText("Scroll"), !style.paged, { change { it.copy(paged = false) } }, Modifier.testTag("manga:novel:scroll"))
                ReadChip(mangaText("Pages"), style.paged, { change { it.copy(paged = true) } }, Modifier.testTag("manga:novel:paged"))
            }
        }
        ReadSheetSection(mangaText("Font")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaNovelStyle.Font.entries.forEach { font -> ReadChip(mangaText(novelFontName(font)), style.font == font, { change { it.copy(font = font) } }) }
            }
        }
        ReadSheetSection(mangaText("Size") + " · ${style.size}") {
            ReadSlider(style.size.toFloat(), { value -> change { it.copy(size = value.roundToInt()) } }, MangaNovelStyle.SIZES.first.toFloat()..MangaNovelStyle.SIZES.last.toFloat(),
                MangaNovelStyle.SIZES.last - MangaNovelStyle.SIZES.first - 1, "Text size", Modifier.testTag("manga:novel:size"))
        }
        ReadSheetSection(mangaText("Line spacing") + " · ${"%.1f".format(style.lineSpacing)}") {
            ReadSlider(style.lineSpacing, { value -> change { it.copy(lineSpacing = (value * 10).roundToInt() / 10f) } }, 1.2f..2.2f, 9, "Line spacing")
        }
        ReadSheetSection(mangaText("Margins") + " · ${style.margin}") {
            ReadSlider(style.margin.toFloat(), { value -> change { it.copy(margin = (value / 8).roundToInt() * 8) } }, 0f..64f, 7, "Margins")
        }
        ReadSheetSection(mangaText("Colours")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaNovelStyle.Theme.entries.forEach { theme -> ReadChip(mangaText(novelThemeName(theme)), style.theme == theme, { change { it.copy(theme = theme) } }) }
            }
        }
    }
}

/** A round reader button with its name as a tooltip on hover or a long press. */
@Composable
private fun NovelButton(icon: ImageVector, label: String, ink: Color, fill: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(),
        tooltip = { PlainTooltip(containerColor = look.ink, contentColor = look.ground) { Text(mangaText(label), style = ReadType.caption(), color = look.ground) } }) {
        OrbitReaderButton(icon, mangaText(label), ink, fill, onClick, modifier)
    }
}

@Composable
private fun NovelBlockText(block: MangaNovelBlock, words: String, body: TextStyle, heading: TextStyle, ink: Color) {
    when (block.kind) {
        MangaNovelBlock.Kind.BREAK -> Text("*   *   *", style = body, color = ink.copy(alpha = 0.55f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        MangaNovelBlock.Kind.HEADING -> Text(words, style = heading)
        MangaNovelBlock.Kind.PARAGRAPH -> Text(words, style = body)
    }
}

/** Paged: the chapter laid out into pages the size of the screen, turned by swiping, tapping the sides or the keys. */
@Composable
private fun NovelPages(text: MangaNovelChapter, column: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, body: TextStyle, heading: TextStyle,
    gap: androidx.compose.ui.unit.Dp, ink: Color, place: MangaNovelPosition, move: NovelMove, controls: Boolean, onTap: (Float, Float) -> Unit,
    onPlace: (MangaNovelPosition, Boolean) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val top = 28.dp
    val bottom = 36.dp
    val widthPx = with(density) { column.roundToPx() }.coerceAtLeast(1)
    // A little room is kept at the foot so a page's last line is never cut by rounding.
    val heightPx = (with(density) { (height - top - bottom).roundToPx() } * 0.97f).toInt().coerceAtLeast(1)
    val gapPx = with(density) { gap.roundToPx() }
    val pages = remember(text, body, heading, widthPx, heightPx, gapPx) {
        val breakHeight = with(density) { body.lineHeight.toPx() }.roundToInt()
        MangaNovelLayout.paginate(text.blocks.map { block ->
            if (block.kind == MangaNovelBlock.Kind.BREAK) listOf(MangaNovelLine(0, 0, 0, breakHeight)) else {
                val layout = measurer.measure(block.text, if (block.kind == MangaNovelBlock.Kind.HEADING) heading else body, constraints = Constraints(maxWidth = widthPx))
                (0 until layout.lineCount).map { MangaNovelLine(layout.getLineStart(it), layout.getLineEnd(it), layout.getLineTop(it).roundToInt(), layout.getLineBottom(it).roundToInt()) }
            }
        }, heightPx, gapPx)
    }
    val pager = rememberPagerState(initialPage = MangaNovelLayout.pageOf(pages, place)) { pages.size }
    // After the pages change (a new size, font or window) the reader turns to the page holding the same words.
    var laidOut by remember { mutableStateOf(pages) }
    LaunchedEffect(pages) { if (laidOut !== pages) { pager.scrollToPage(MangaNovelLayout.pageOf(pages, place)); laidOut = pages } }
    LaunchedEffect(pager.settledPage, laidOut) {
        if (laidOut === pages) pages.getOrNull(pager.settledPage)?.let { onPlace(it.start, pager.settledPage >= pages.lastIndex) }
    }
    LaunchedEffect(move) {
        if (move.nonce == 0L) return@LaunchedEffect
        val target = move.place?.let { MangaNovelLayout.pageOf(pages, it) } ?: (pager.settledPage + move.by)
        pager.animateScrollToPage(target.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }
    HorizontalPager(pager, modifier = Modifier.fillMaxSize().testTag("manga:novel:pages"), beyondViewportPageCount = 1) { index ->
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap(it.x, size.width.toFloat()) } }) {
            Column(Modifier.align(Alignment.TopCenter).width(column).padding(top = top).height(height - top - bottom).clipToBounds(),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                pages.getOrNull(index)?.slices?.forEach { slice ->
                    val block = text.blocks[slice.block]
                    NovelBlockText(block, block.text.substring(slice.start.coerceIn(0, block.text.length), slice.end.coerceIn(0, block.text.length)).trimEnd('\n'), body, heading, ink)
                }
            }
            if (!controls) Text("${index + 1} / ${pages.size}", style = ReadType.caption(), color = ink.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).testTag("manga:novel:page"))
        }
    }
}

/** Scrolling: one column of the chapter's blocks, with the next chapter at its end. */
@Composable
private fun NovelScroll(text: MangaNovelChapter, column: androidx.compose.ui.unit.Dp, body: TextStyle, heading: TextStyle, gap: androidx.compose.ui.unit.Dp, ink: Color,
    place: MangaNovelPosition, move: NovelMove, hasNext: Boolean, onTap: (Float, Float) -> Unit, onPlace: (MangaNovelPosition, Boolean) -> Unit, onNext: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = place.block)
    // The place is kept to the character; once the block it is in has been measured, the column goes that far into it.
    var restored by remember { mutableStateOf(false) }
    suspend fun restore(to: MangaNovelPosition) {
        list.scrollToItem(to.block)
        androidx.compose.runtime.withFrameNanos { }
        val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == to.block }?.size ?: 0
        val length = text.blocks.getOrNull(to.block)?.text?.length ?: 0
        if (to.char > 0) list.scrollToItem(to.block, MangaNovelLayout.offsetFor(to.char, length, size))
    }
    LaunchedEffect(body, heading, column) { restore(place); restored = true }
    LaunchedEffect(move) {
        if (move.nonce == 0L) return@LaunchedEffect
        val target = move.place
        if (target != null) restore(target) else list.animateScrollBy(move.by * list.layoutInfo.viewportSize.height * 0.85f)
    }
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            Triple(index, list.firstVisibleItemScrollOffset, size) to !list.canScrollForward
        }.distinctUntilChanged().collect { (at, end) ->
            val (index, offset, size) = at
            val block = index.coerceAtMost(text.blocks.lastIndex)
            val char = if (index > text.blocks.lastIndex) 0 else MangaNovelLayout.charAt(text.blocks[block].text.length, offset, size)
            onPlace(MangaNovelPosition(block, char), end)
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("manga:novel:scroll-column").pointerInput(Unit) { detectTapGestures { onTap(it.x, size.width.toFloat()) } },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(top = 28.dp, bottom = 120.dp)) {
        itemsIndexed(text.blocks) { _, block -> Box(Modifier.width(column)) { NovelBlockText(block, block.text, body, heading, ink) } }
        item(key = "novel:end") {
            Column(Modifier.width(column).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(mangaText("End of chapter"), style = ReadType.meta(), color = ink.copy(alpha = 0.6f))
                ReadButton(mangaText(if (hasNext) "Next chapter" else "Finish"), onNext, modifier = Modifier.testTag("manga:novel:next"))
            }
        }
    }
}
