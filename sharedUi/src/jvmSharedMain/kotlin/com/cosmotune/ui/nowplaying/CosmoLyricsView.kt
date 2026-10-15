package com.cosmotune.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.design.TypeScale
import com.cosmotune.design.LyricsMetrics.LineState
import com.cosmotune.lyrics.SungFraction
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.romanize_lyrics
import com.music.bitchord.sharedui.resources.lyrics_not_available
import com.music.bitchord.sharedui.resources.show_original_lyrics
import com.music.bitchord.sharedui.resources.translate_lyrics
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.player.LyricsTranslationUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * How a lyrics view is set: the step the lines are laid out at ([baseStep], a [com.cosmotune.design.TypeScale] step), the scale
 * the line being sung is drawn at and the scale of the lines already sung. A scale is a graphics layer from the line's start
 * edge, never a different font size, so the list is laid out once and does not lay out again as the song moves on (the board
 * changes the font size from line to line; this is a deliberate departure from it).
 */
@Immutable
data class LyricsStyle(val baseStep: Int, val currentScale: Float, val pastScale: Float) {
    companion object {
        /** A phone held upright: title-l, the line being sung 1.2 times as big, the lines already sung 0.86. */
        val Portrait = LyricsStyle(LyricsMetrics.PORTRAIT_BASE_STEP, LyricsMetrics.PORTRAIT_CURRENT_SCALE, LyricsMetrics.PORTRAIT_PAST_SCALE)

        /** A phone on its side: the same scales one step down, there is less room. */
        val Landscape = LyricsStyle(LyricsMetrics.LANDSCAPE_BASE_STEP, LyricsMetrics.PORTRAIT_CURRENT_SCALE, LyricsMetrics.PORTRAIT_PAST_SCALE)
    }
}

/**
 * The lyrics: a list of lines, [LyricsMetrics.LINE_GAP_DP] apart, that follows the song.
 *
 * Every line is the same text laid out once at `title(style.baseStep)` (at a width that leaves room for the largest scale it
 * will be drawn at). With synced [lines]:
 *  - lines already sung are `textTertiary` at 80 % and drawn at `style.pastScale`; lines still to come are `textSecondary` at
 *    78 %; the colours and the scale change on the slow spring (reduced motion: no scale, a short fade of the colour);
 *  - the line being sung is `textPrimary` with a second copy of the same text on top in `accentStrong`, clipped to the part
 *    that has been sung: the clock decides it every frame in the draw phase, so a frame of the fill redraws one line and
 *    recomposes nothing. A line that wraps fills in reading order, one visual line after the other.
 * Without timings every line is `textPrimary` and still. Romanisation or translation ([subLines], index for index) sits under
 * each line. A tap on a line seeks to it ([onSeekToLine]); the list scrolls to keep the line being sung a third of the way
 * down, and a finger on the list stops that for 2.5 seconds. No lines at all draws [emptyText] in the middle.
 */
@Composable
internal fun CosmoLyricsView(
    lines: List<LyricLine>,
    subLines: List<LyricLine>?,
    trackKey: String,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    style: LyricsStyle,
    modifier: Modifier = Modifier,
    emptyText: String = stringResource(Res.string.lyrics_not_available),
    topPadding: Dp = LyricsMetrics.PORTRAIT_TOP_PADDING_DP.dp,
) {
    if (lines.isEmpty()) {
        NoLyrics(emptyText, modifier)
        return
    }
    // A new song is a new list, scrolled to its own place: the state of every line starts again.
    key(trackKey) {
        LyricsList(lines, subLines, trackKey, playhead, isPlaying, onSeekToLine, style, topPadding, modifier)
    }
}

/** "No lyrics", in the middle of the space the lyrics would have had. */
@Composable
private fun NoLyrics(text: String, modifier: Modifier) {
    val type = LocalCosmoType.current
    val ink = Color(LocalCosmoColors.current.palette.textSecondary)
    val textStyle = remember(type) { type.title(TypeScale.TITLE_S).copy(textAlign = TextAlign.Center) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text, style = textStyle.copy(color = ink))
    }
}

/** The colours of a line in each of its states, resolved from the palette once per cover. */
@Immutable
private class LyricInk(val primary: Color, val past: Color, val upcoming: Color, val accent: Color, val secondary: Color)

private fun inkOf(palette: CoverColors) = LyricInk(
    primary = Color(palette.textPrimary),
    past = Color(palette.textTertiary).copy(alpha = LyricsMetrics.alphaFor(LineState.PAST)),
    upcoming = Color(palette.textSecondary).copy(alpha = LyricsMetrics.alphaFor(LineState.UPCOMING)),
    accent = Color(palette.accentStrong),
    secondary = Color(palette.textSecondary),
)

@Composable
private fun LyricsList(
    lines: List<LyricLine>,
    subLines: List<LyricLine>?,
    trackKey: String,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    style: LyricsStyle,
    topPadding: Dp,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette      // the palette being shown: changes once per cover
    val ink = remember(palette) { inkOf(palette) }

    // Hoisted from the brief's derived state: whether the song has timings is a fact about the lines, not about the clock.
    val synced = remember(lines) { SungFraction.isSynced(lines) }
    val clock = rememberCosmoLyricClock(trackKey, playhead, isPlaying && synced)
    val current = remember(lines, clock, synced) {
        derivedStateOf { if (synced) currentLineAt(lines, clock.longValue) else -1 }
    }

    // ---- following the song ----------------------------------------------------------------------------------------------
    val listState = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
    val userScrolls = remember { mutableIntStateOf(0) }
    val watcher = remember { UserScrollWatcher(userScrolls) }
    LaunchedEffect(Unit) {
        // A drag (or a wheel) pauses the following; two and a half seconds after the last one it carries on.
        snapshotFlow { userScrolls.intValue }.collectLatest { count ->
            if (count > 0) {
                browsing = true
                delay(LyricsMetrics.FOLLOW_PAUSE_MS)
                browsing = false
            }
        }
    }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(synced, browsing, reduced) {
        if (!synced || browsing) return@LaunchedEffect
        snapshotFlow { current.value }.collectLatest { index ->
            if (index < 0) return@collectLatest
            val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            val offset = LyricsMetrics.followOffsetPx(viewport)
            // The first place is taken at once; after that each line is scrolled to (reduced motion: taken at once too).
            if (!placed || reduced) {
                listState.scrollToItem(index, offset)
                placed = true
            } else {
                listState.animateScrollToItem(index, offset)
            }
        }
    }

    // ---- the lines -------------------------------------------------------------------------------------------------------
    val baseStyle = remember(type, style.baseStep) { type.title(style.baseStep) }
    val subStyle = remember(type) {
        TextStyle(
            fontFamily = type.textFamily,
            fontSize = LyricsMetrics.SUB_SIZE_SP.sp,
            lineHeight = (LyricsMetrics.SUB_SIZE_SP * 1.35f).sp,
            fontWeight = FontWeight(LyricsMetrics.SUB_WEIGHT),
        )
    }
    val viewportPx by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val bottomRoom = with(LocalDensity.current) { LyricsMetrics.bottomRoomPx(viewportPx).toDp() }
    LazyColumn(
        state = listState,
        modifier = modifier.nestedScroll(watcher).fadeEdges(FADE_EDGE),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomRoom),
        verticalArrangement = Arrangement.spacedBy(LyricsMetrics.LINE_GAP_DP.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val sub = subLines?.getOrNull(index)?.text?.takeIf { it.isNotBlank() && it != line.text }
            LyricItem(
                index = index, lines = lines, sub = sub, current = current, synced = synced, clock = clock, ink = ink,
                textStyle = remember(baseStyle, line.text) { type.forText(baseStyle, line.text) }, subStyle = subStyle,
                style = style, reduced = reduced, onSeekToLine = onSeekToLine,
            )
        }
    }
}

/**
 * [SungFraction.currentIndex], the last line whose stamp has passed, without the iterator that one allocates: this runs on every
 * frame of the song, and nothing is allocated per frame in steady state. The same answer for any list, sorted or not.
 */
internal fun currentLineAt(lines: List<LyricLine>, positionMs: Long): Int {
    var i = lines.size - 1
    while (i >= 0) {
        if (lines[i].timeMs <= positionMs) return i
        i--
    }
    return -1
}

/** Counts the scrolls that come from a person (a finger, a wheel); the list's own scrolling to the next line is not one. */
private class UserScrollWatcher(private val count: MutableIntState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) count.intValue++
        return Offset.Zero
    }
}

/** How far the top and the bottom of the list fade out. */
private val FADE_EDGE = 28.dp

/** One line, its romanisation or translation under it, and, while it is being sung, the accent copy that fills in. */
@Composable
private fun LyricItem(
    index: Int,
    lines: List<LyricLine>,
    sub: String?,
    current: State<Int>,
    synced: Boolean,
    clock: MutableLongState,
    ink: LyricInk,
    textStyle: TextStyle,
    subStyle: TextStyle,
    style: LyricsStyle,
    reduced: Boolean,
    onSeekToLine: (Long) -> Unit,
) {
    val line = lines[index]
    // Only the three lines whose state changes recompose when the song moves on.
    val state = remember(index, synced, current) {
        derivedStateOf { if (synced) LyricsMetrics.lineState(index, current.value) else LineState.UPCOMING }
    }
    val lineState = state.value
    if (line.isGap) {
        GapDots(index, lines, state, clock, ink, reduced)
        return
    }

    val target = when {
        !synced -> ink.primary
        lineState == LineState.CURRENT -> ink.primary
        lineState == LineState.PAST -> ink.past
        else -> ink.upcoming
    }
    val color = animateColorAsState(target, CosmoMotion.effects(reduced), label = "lyricInk")
    val scale = animateFloatAsState(
        LyricsMetrics.scaleFor(lineState, style.currentScale, style.pastScale, reduced), CosmoMotion.slow(reduced), label = "lyricScale",
    )
    val fill = animateFloatAsState(if (synced && lineState == LineState.CURRENT) 1f else 0f, CosmoMotion.effects(reduced), label = "lyricFill")
    // The accent copy is composed from the moment the line is current until it has faded out after it.
    val fillOn by remember { derivedStateOf { fill.value > 0f } }
    val baseInk = remember(color) { ColorProducer { color.value } }
    val subInk = remember(ink) { ColorProducer { ink.secondary } }
    val accentInk = remember(ink) { ColorProducer { ink.accent } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val layoutRef = remember { LayoutHolder() }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = synced, role = Role.Button,
            ) { onSeekToLine(line.timeMs) }
            .graphicsLayer {
                val k = scale.value
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
            }
            // Laid out narrow enough that the line still fits the list when it is drawn at its largest scale.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = LyricsMetrics.layoutWidthPx(constraints.maxWidth, style.currentScale)),
                )
                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
            },
    ) {
        Box {
            BasicText(text = line.text, style = textStyle, color = baseInk)
            if (fillOn) {
                BasicText(
                    text = line.text,
                    style = textStyle,
                    color = accentInk,
                    onTextLayout = { layoutRef.value = it },
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { alpha = fill.value }
                        .drawWithContent { sweepFill(layoutRef.value, SungFraction.of(lines, index, clock.longValue)) },
                )
            }
        }
        if (sub != null) {
            BasicText(text = sub, style = subStyle, color = subInk, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The last text layout of the accent copy, kept for the draw phase (no state: it is written before the draw that reads it). */
private class LayoutHolder { var value: TextLayoutResult? = null }

/**
 * Draws the content of the node as far as [fraction] of its text has been sung. A line that wraps fills in reading order: the
 * visual lines above the one holding the edge are drawn whole, that one from its start edge to the edge of the sung part (a
 * right-to-left line from its right edge), the ones below not at all. The edge moves through a character, not from one to the next.
 */
internal fun ContentDrawScope.sweepFill(layout: TextLayoutResult?, fraction: Float) {
    if (layout == null || fraction <= 0f) return
    val length = layout.layoutInput.text.length
    if (length == 0) return
    if (fraction >= 1f) {
        drawContent()
        return
    }
    val chars = LyricsMetrics.sungChars(fraction, length)
    val whole = chars.toInt().coerceAtMost(length - 1)
    val boundary = layout.getLineForOffset(whole)
    if (boundary > 0) {
        clipRect(left = 0f, top = 0f, right = size.width, bottom = layout.getLineTop(boundary)) { this@sweepFill.drawContent() }
    }
    val lineEnd = layout.getLineEnd(boundary, visibleEnd = false)
    val x0 = layout.getHorizontalPosition(whole, usePrimaryDirection = true)
    val x1 = if (whole + 1 >= lineEnd) {
        if (layout.getParagraphDirection(whole) == ResolvedTextDirection.Rtl) layout.getLineLeft(boundary) else layout.getLineRight(boundary)
    } else {
        layout.getHorizontalPosition(whole + 1, usePrimaryDirection = true)
    }
    val edge = x0 + (x1 - x0) * (chars - whole)
    val top = layout.getLineTop(boundary)
    val bottom = layout.getLineBottom(boundary)
    if (layout.getParagraphDirection(whole) == ResolvedTextDirection.Rtl) {
        clipRect(left = edge, top = top, right = layout.getLineRight(boundary), bottom = bottom) { this@sweepFill.drawContent() }
    } else {
        clipRect(left = layout.getLineLeft(boundary), top = top, right = edge, bottom = bottom) { this@sweepFill.drawContent() }
    }
}

/**
 * An instrumental (a line with no words). Between verses it is no more than room; while it is the line being sung it opens to
 * show three dots that light one after the other across it, so a long break reads as time running down.
 */
@Composable
private fun GapDots(
    index: Int,
    lines: List<LyricLine>,
    state: State<LineState>,
    clock: MutableLongState,
    ink: LyricInk,
    reduced: Boolean,
) {
    val line = lines[index]
    val open = animateFloatAsState(if (state.value == LineState.CURRENT) 1f else 0f, CosmoMotion.move(reduced), label = "gapOpen")
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            // Closed it takes no height at all (the gap between two lines is all a verse break needs); open, the whole row.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * open.value.coerceIn(0f, 1f)).roundToInt()) { placeable.placeRelative(0, 0) }
            }
            .height((LyricsMetrics.GAP_DOT_DP + 2f * LyricsMetrics.GAP_DOT_GAP_DP).dp)
            .drawBehind {
                // Closed, nothing is drawn and the clock is not read: a break far from the line being sung redraws never.
                val opened = open.value.coerceIn(0f, 1f)
                if (opened <= 0f) return@drawBehind
                val radius = LyricsMetrics.GAP_DOT_DP.dp.toPx() / 2f
                val stride = (LyricsMetrics.GAP_DOT_DP + LyricsMetrics.GAP_DOT_GAP_DP).dp.toPx()
                val until = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + SungFraction.LAST_LINE_MS)
                val through = ((clock.longValue - line.timeMs).toFloat() / (until - line.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                repeat(3) { dot ->
                    val lit = (through * 3f - dot).coerceIn(0f, 1f)
                    drawCircle(
                        color = ink.secondary.copy(alpha = (0.28f + 0.72f * lit) * opened),
                        radius = radius,
                        center = Offset(radius + dot * stride, size.height / 2f),
                    )
                }
            },
    )
}

/** Fades the top and the bottom [edge] of the content out. One brush per size, drawn over an offscreen copy of the list. */
private fun Modifier.fadeEdges(edge: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val height = size.height
        if (height <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
        val e = minOf(edge.toPx(), height / 2f)
        val brush = Brush.verticalGradient(
            colorStops = arrayOf(0f to Color.Transparent, e / height to Color.Black, 1f - e / height to Color.Black, 1f to Color.Transparent),
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush, blendMode = BlendMode.DstIn)
        }
    }

/**
 * The lyrics and what goes with them, in the space the page gives them: the view above and, under it, one line that says where
 * the words come from (a tap on it opens the provider sheet), with the two buttons that ask for a romanisation or a translation.
 * [loadingText] stands in while the lookup is still running, [lyrics] being null until it has come back.
 */
@Composable
internal fun CosmoLyricsPanel(
    lyrics: List<LyricLine>?,
    lyricsUnavailable: Boolean,
    loadingText: String,
    translation: LyricsTranslationUi,
    trackKey: String,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    onOpenProviders: () -> Unit,
    style: LyricsStyle,
    modifier: Modifier = Modifier,
    topPadding: Dp = LyricsMetrics.PORTRAIT_TOP_PADDING_DP.dp,
) {
    val lines = lyrics.orEmpty()
    Column(modifier) {
        CosmoLyricsView(
            lines = lines,
            subLines = translation.subLines,
            trackKey = trackKey,
            playhead = playhead,
            isPlaying = isPlaying,
            onSeekToLine = onSeekToLine,
            style = style,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            emptyText = if (lyrics == null && !lyricsUnavailable) loadingText else stringResource(Res.string.lyrics_not_available),
            topPadding = topPadding,
        )
        // While the lookup runs the middle of the panel says so; the footer would say the same line again.
        val looking = lyrics == null && !lyricsUnavailable
        LyricsFooter(translation.status.takeUnless { looking && it == loadingText }.orEmpty(), translation, hasLines = lines.isNotEmpty(), onOpenProviders = onOpenProviders)
    }
}

/** The line that names the source (or what the translation is doing) and the two toggles; 48 dp tall. */
@Composable
private fun LyricsFooter(status: String, translation: LyricsTranslationUi, hasLines: Boolean, onOpenProviders: () -> Unit) {
    val type = LocalCosmoType.current
    val ink = Color(LocalCosmoColors.current.palette.textSecondary)
    val statusStyle = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 13.sp, fontWeight = FontWeight(600), textDecoration = TextDecoration.Underline) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status.isNotEmpty()) {
            BasicText(
                text = status,
                style = statusStyle.copy(color = ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(CosmoRadii.xs))
                    .clickable(role = Role.Button, onClick = onOpenProviders)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        CosmoIconButton(
            Icons.Rounded.Language,
            stringResource(if (translation.showingRomanization) Res.string.show_original_lyrics else Res.string.romanize_lyrics),
            translation.toggleRomanization,
            style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = translation.showingRomanization, enabled = hasLines,
        )
        CosmoIconButton(
            Icons.Rounded.Translate,
            stringResource(if (translation.showingTranslation) Res.string.show_original_lyrics else Res.string.translate_lyrics),
            translation.toggleTranslation,
            style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = translation.showingTranslation, enabled = hasLines,
        )
    }
}
