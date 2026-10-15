package com.cosmotune.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.design.LyricsMetrics.LineState
import com.cosmotune.design.TypeScale
import com.cosmotune.lyrics.LyricActivity
import com.cosmotune.lyrics.LyricBacking
import com.cosmotune.lyrics.LyricDuet
import com.cosmotune.lyrics.LyricRows
import com.cosmotune.lyrics.SungFraction
import com.cosmotune.lyrics.WordFill
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.instrumental
import com.music.bitchord.sharedui.resources.lyrics_not_available
import com.music.bitchord.sharedui.resources.romanize_lyrics
import com.music.bitchord.sharedui.resources.share
import com.music.bitchord.sharedui.resources.show_original_lyrics
import com.music.bitchord.sharedui.resources.translate_lyrics
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.player.LyricsTranslationMotion
import com.music.bitchord.ui.player.LyricsTranslationUi
import com.music.bitchord.ui.player.LyricsTranslationUiState
import com.music.bitchord.ui.player.PlayerSettings
import com.music.bitchord.ui.player.renderEffectBlurSupported
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs
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
 *    recomposes nothing. A line that wraps fills in reading order, one visual line after the other. A line with word timings
 *    fills a word at a time: the edge crosses each timed piece at a steady rate and fades out over [LyricsMetrics.WIPE_FEATHER_DP];
 *    the word being sung lifts off the line, and a held note swells a letter at a time with a bloom behind it (not with reduced motion);
 *  - a line still being sung while a later one has begun (the second voice of a duet) is current as well.
 * Without timings every line is `textPrimary` and still. A duet is laid out on two sides, each voice keeping its lane; a second
 * voice under a line (an echo, an answer) opens under it only while it is sung. Romanisation or translation ([subLines], index for
 * index) opens under each line, with motes rising off it as it changes ([particles]). A tap on a line seeks to it
 * ([onSeekToLine]), or, while [selection] is picking lines for a card, adds or drops it; a long press starts a pick. The list
 * scrolls to keep the line being sung a third of the way down, and a finger on the list (or a pick) stops that for 2.5 seconds.
 * No lines at all draws [emptyText] in the middle.
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
    selection: LyricSelection = LyricSelection.None,
    particles: State<Float>? = null,
) {
    if (lines.isEmpty()) {
        NoLyrics(emptyText, modifier)
        return
    }
    // A new song is a new list, scrolled to its own place: the state of every line starts again.
    key(trackKey) {
        LyricsList(lines, subLines, trackKey, playhead, isPlaying, onSeekToLine, style, topPadding, selection, particles, modifier)
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
internal class LyricInk(val primary: Color, val past: Color, val upcoming: Color, val accent: Color, val secondary: Color)

private fun inkOf(palette: CoverColors) = LyricInk(
    primary = Color(palette.textPrimary),
    past = Color(palette.textTertiary).copy(alpha = LyricsMetrics.alphaFor(LineState.PAST)),
    upcoming = Color(palette.textSecondary).copy(alpha = LyricsMetrics.alphaFor(LineState.UPCOMING)),
    accent = Color(palette.accentStrong),
    secondary = Color(palette.textSecondary),
)

/**
 * What every row of the list shares: the song's lines and what is known about them, the clock, the colours and type, and what a
 * touch does. Made once per change of any of them (never per frame), so a row recomposes only when one of these is replaced.
 */
@Stable
internal class LyricEnv(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val duet: Boolean,
    val clock: MutableLongState,
    /** The last line whose stamp has passed. */
    val current: State<Int>,
    /** The uppermost line still being sung: where the list follows. */
    val anchor: State<Int>,
    val activity: LyricActivity,
    val ink: LyricInk,
    val type: CosmoType,
    val baseStyle: TextStyle,
    val subStyle: TextStyle,
    val style: LyricsStyle,
    val reduced: Boolean,
    val rtl: Boolean,
    val selection: LyricSelection,
    val particles: State<Float>?,
    val subReveal: SubRevealState,
    /** Whether the bloom behind a held note may be drawn: the app's settings and the platform allow the blur, and the motion is not reduced. */
    val bloom: Boolean,
    val onSeek: (Long) -> Unit,
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
    selection: LyricSelection,
    particles: State<Float>?,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette      // the palette being shown: changes once per cover
    val ink = remember(palette) { inkOf(palette) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Hoisted from the brief's derived state: whether the song has timings is a fact about the lines, not about the clock.
    val synced = remember(lines) { SungFraction.isSynced(lines) }
    val clock = rememberCosmoLyricClock(trackKey, playhead, isPlaying && synced)
    val activity = remember(lines) { LyricActivity(lines) }
    // Only a song that names a second voice is laid out as a duet; a single voice already has every line on the start side.
    val duet = remember(lines) { LyricDuet.isDuet(lines) }
    val current = remember(lines, clock, synced) {
        derivedStateOf { if (synced) currentLineAt(lines, clock.longValue) else -1 }
    }
    // The uppermost unfinished vocal owns the scroll anchor until its end, even as later rows begin their own fill.
    val anchor = remember(activity, clock, synced, current) {
        derivedStateOf { if (synced) activity.firstActive(current.value, clock.longValue) else -1 }
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
    // While lines are being picked the list stays put: the reader needs it to, to tap the lines they want.
    val following = !browsing && !selection.picking
    LaunchedEffect(synced, following, reduced) {
        if (!synced || !following) return@LaunchedEffect
        snapshotFlow { anchor.value }.collectLatest { index ->
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
    val subReveal = rememberSubReveal(subLines, trackKey, reduced)
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val lyricsBlur by PlayerSettings.lyricsBlur.collectAsStateWithLifecycle()
    val bloom = !reduced && !reduceDynamicBlur && lyricsBlur && renderEffectBlurSupported
    // The seek is read at the moment of the tap, so a new lambda from the caller does not replace every row.
    val seek by rememberUpdatedState(onSeekToLine)
    val onSeek = remember { { timeMs: Long -> seek(timeMs) } }
    val env = remember(
        lines, synced, duet, clock, current, anchor, activity, ink, type, baseStyle, subStyle, style, reduced, rtl, selection, particles,
        subReveal, bloom,
    ) {
        LyricEnv(
            lines, synced, duet, clock, current, anchor, activity, ink, type, baseStyle, subStyle, style, reduced, rtl, selection,
            particles, subReveal, bloom, onSeek,
        )
    }
    val viewportPx by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val bottomRoom = with(LocalDensity.current) { LyricsMetrics.bottomRoomPx(viewportPx).toDp() }
    LazyColumn(
        state = listState,
        // The list is a little wider than the column the words keep to, so that the wash of a picked line has room beside them.
        modifier = modifier.bleedHorizontally(WASH_BLEED).nestedScroll(watcher).fadeEdges(FADE_EDGE),
        contentPadding = PaddingValues(start = WASH_BLEED, end = WASH_BLEED, top = topPadding, bottom = bottomRoom),
        verticalArrangement = Arrangement.spacedBy(LyricsMetrics.LINE_GAP_DP.dp),
    ) {
        itemsIndexed(lines) { index, _ -> LyricItem(index, env) }
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

/** How far the wash behind a line reaches beside its words, and so how much wider than their column the list is. */
private val WASH_BLEED = 8.dp

/** Lays [this] out [amount] wider on each side and places it that far out, so that its content padding gives back what it took. */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier = this.layout { measurable, constraints ->
    val bleed = amount.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * bleed,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * bleed else constraints.maxWidth,
        ),
    )
    layout((placeable.width - 2 * bleed).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-bleed, 0) }
}

/** What the wash behind a picked line, and behind the others while lines are being picked, is made of. */
private const val PICKED_WASH = 0.22f
private const val OTHER_WASH = 0.06f

/**
 * One line, its romanisation or translation under it, the second voice under that, and, while it is being sung, the accent copy
 * that fills in: a header marker or a break in a plain-text song, a row of dots for an instrumental in a synced one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricItem(index: Int, env: LyricEnv) {
    val line = env.lines[index]
    val synced = env.synced
    if (LyricRows.isHeader(line, synced)) {
        LyricSectionHeader(line.text, first = index == 0, env = env)
        return
    }
    // Only the lines whose state changes recompose when the song moves on; a line that cannot be kept being sung past the next
    // one's stamp never reads the clock for it.
    val overlaps = remember(env.activity, index) { env.activity.mayOverlap(index) }
    val state = remember(index, synced, env.current, env.clock, env.activity, overlaps) {
        derivedStateOf {
            if (!synced) {
                LineState.UPCOMING
            } else {
                val current = env.current.value
                LyricsMetrics.lineState(index, current, overlaps && env.activity.isActive(index, current, env.clock.longValue))
            }
        }
    }
    val lineState = state.value
    if (line.isGap) {
        if (!synced) {
            // A verse break in a plain-text song is room and nothing else.
            Spacer(Modifier.height(LyricsMetrics.LINE_GAP_DP.dp))
        } else {
            GapDots(index, env.lines, state, env.clock, env.ink, env.reduced, seekable = LyricRows.breakSeeks(synced, env.selection.picking), onSeek = env.onSeek)
        }
        return
    }

    val reduced = env.reduced
    val ink = env.ink
    val picking = env.selection.picking
    val onEnd = LyricDuet.onEnd(env.duet, line)
    val laneDp = LyricDuet.laneDp(env.duet)
    val align = if (onEnd) TextAlign.End else TextAlign.Start
    val textStyle = remember(env.baseStyle, line.text, align) { env.type.forText(env.baseStyle.copy(textAlign = align), line.text) }
    val subStyle = remember(env.subStyle, align) { env.subStyle.copy(textAlign = align) }
    val sub = env.subReveal.lines?.getOrNull(index)
    val subText = sub?.text?.takeIf { it.isNotBlank() && it != line.text }
    // The second voice as it is drawn (without the brackets text-only sources wrap it in), and its own translation if that says anything new.
    val backing = remember(line) { line.background?.let { LyricBacking.display(it) } }
    val subBackingText = if (backing == null) null else sub?.background?.text?.takeIf { it.isNotBlank() && LyricBacking.differs(it, backing.text) }

    val target = when {
        !synced -> ink.primary
        lineState == LineState.CURRENT -> ink.primary
        lineState == LineState.PAST -> ink.past
        else -> ink.upcoming
    }
    val color = animateColorAsState(target, CosmoMotion.effects(reduced), label = "lyricInk")
    val scale = animateFloatAsState(
        LyricsMetrics.scaleFor(lineState, env.style.currentScale, env.style.pastScale, reduced), CosmoMotion.slow(reduced), label = "lyricScale",
    )
    val fill = animateFloatAsState(if (synced && lineState == LineState.CURRENT) 1f else 0f, CosmoMotion.effects(reduced), label = "lyricFill")
    // The accent copy is composed from the moment the line is current until it has faded out after it.
    val fillOn by remember { derivedStateOf { fill.value > 0f } }
    val baseInk = remember(color) { ColorProducer { color.value } }
    val subInk = remember(ink) { ColorProducer { ink.secondary } }
    val accentInk = remember(ink) { ColorProducer { ink.accent } }
    val layoutRef = remember { LayoutHolder() }
    val subLayoutRef = remember(sub) { LayoutHolder() }

    // Word timings are what the word effects run on. A line without them fills over its slot, and moves not at all.
    val wordSynced = synced && line.isWordSynced
    val lifter = remember(line) { if (wordSynced) WordLifter(line) else null }
    // Where the line is in its own animation, which changes twice a line rather than sixty times a second: the lines that are not
    // moving never read the clock, so a line nobody is singing never redraws.
    val moving = remember(line, env.clock) {
        derivedStateOf { env.clock.longValue.let { it >= line.animatesFromMs && it <= line.animatesUntilMs } }
    }
    val glow = animateFloatAsState(
        if (env.bloom && lifter?.hasHeldWords == true && lineState == LineState.CURRENT) LyricsMetrics.GLOW_ALPHA else 0f,
        CosmoMotion.effects(reduced), label = "lyricGlow",
    )
    val glowOn by remember { derivedStateOf { glow.value > 0.01f } }

    // The mark of a pick: the chosen lines lit with the cover's colour, the others stepped back.
    val mark = LyricRows.mark(index, picking, env.selection.picked)
    val wash = animateColorAsState(
        when (mark) {
            LyricRows.Mark.PICKED -> ink.accent.copy(alpha = PICKED_WASH)
            LyricRows.Mark.OTHER -> ink.primary.copy(alpha = OTHER_WASH)
            LyricRows.Mark.NONE -> Color.Transparent
        },
        CosmoMotion.effects(reduced), label = "lyricWash",
    )
    val interaction = remember { MutableInteractionSource() }
    // A line under a finger dips, the way a button does (not with reduced motion).
    val pressed by interaction.collectIsPressedAsState()
    val dip = animateFloatAsState(LyricsMetrics.pressedScale(pressed, reduced), CosmoMotion.press(reduced), label = "lyricPress")
    val canPick = env.selection.canPick
    val longPress = LyricRows.onLongPress(canPick, synced, picking)
    val shareLabel = stringResource(Res.string.share)

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = LyricRows.responds(synced, picking),
                role = Role.Button,
                onLongClickLabel = if (longPress == LyricRows.OnLongPress.PICK) shareLabel else null,
                onLongClick = if (longPress == LyricRows.OnLongPress.PICK) ({ env.selection.onPick(index) }) else null,
                onClick = {
                    when (LyricRows.onTap(synced, env.selection.picking)) {
                        LyricRows.OnTap.SEEK -> env.onSeek(line.timeMs)
                        LyricRows.OnTap.TOGGLE_PICK -> env.selection.onToggle(index)
                        LyricRows.OnTap.NOTHING -> Unit
                    }
                },
            )
            .then(if (picking) Modifier.semantics { selected = mark == LyricRows.Mark.PICKED } else Modifier)
            .graphicsLayer {
                val k = scale.value * dip.value
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(LyricsMetrics.originFraction(onEnd, env.rtl), 0.5f)
            }
            // Laid out narrow enough that the line still fits the list when it is drawn at its largest scale, and, in a duet,
            // in what is left after the lane kept clear for the other voice; placed against the edge it is written from.
            .layout { measurable, constraints ->
                val lanePx = laneDp.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = LyricsMetrics.layoutWidthPx(constraints.maxWidth, lanePx, env.style.currentScale)),
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeRelative(LyricsMetrics.placeOffsetPx(constraints.maxWidth, placeable.width, onEnd), 0)
                }
            }
            // The wash is drawn a little outside the line, so that its words are not pressed against its edge.
            .drawBehind {
                val washColor = wash.value
                if (washColor.alpha > 0f) {
                    val padX = WASH_BLEED.toPx()
                    val padY = 4.dp.toPx()
                    drawRoundRect(
                        washColor, Offset(-padX, -padY), Size(size.width + 2f * padX, size.height + 2f * padY), CornerRadius(12.dp.toPx()),
                    )
                }
            },
    ) {
        Box(Modifier.fillMaxWidth()) {
            // The bloom of a held note, behind the words: a blurred copy lit only on the letters being held.
            if (glowOn && lifter != null) {
                Bloom(line, textStyle, layoutRef, env, lifter, glow)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    // Both copies are lifted together: the word being sung rises off the line, the ones behind it settle back.
                    .then(
                        if (lifter != null && !reduced) {
                            Modifier.drawWithContent {
                                val layout = layoutRef.value
                                if (layout != null && moving.value && WordFill.isLifted(line, env.clock.longValue)) {
                                    lifter.lift(this, layout, env.clock.longValue)
                                } else {
                                    drawContent()
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                BasicText(
                    text = line.text,
                    style = textStyle,
                    color = baseInk,
                    onTextLayout = { layoutRef.value = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fillOn) {
                    BasicText(
                        text = line.text,
                        style = textStyle,
                        color = accentInk,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics { }
                            // The feather erases into this layer, so the layer has to exist; a line without word timings has no feather.
                            .graphicsLayer {
                                alpha = fill.value
                                compositingStrategy = if (wordSynced) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                            }
                            .drawWithCache {
                                val feather = if (wordSynced) featherEdge() else null
                                onDrawWithContent {
                                    if (wordSynced) {
                                        sweepWords(layoutRef.value, line, env.clock.longValue, feather)
                                    } else {
                                        sweepFill(layoutRef.value, SungFraction.of(env.lines, index, env.clock.longValue))
                                    }
                                }
                            },
                    )
                }
            }
        }
        if (subText != null) {
            // The romanisation or translation, opening out of the line above it; a line the service handed back unchanged gets no second copy.
            val particlesOn = env.particles != null && if (synced) abs(index - env.anchor.value) <= 1 else index < 4
            BasicText(
                text = subText,
                style = subStyle,
                color = subInk,
                onTextLayout = { subLayoutRef.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .revealBelow(env.subReveal.progress)
                    .translationParticles({ subLayoutRef.value }, env.particles.takeIf { particlesOn }, ink.accent)
                    .padding(top = 2.dp),
            )
        }
        if (backing != null) {
            BackingVoice(line.background!!, backing, env, remember(state) { derivedStateOf { state.value == LineState.CURRENT } }, onEnd, subBackingText)
        }
    }
}

/**
 * The bloom behind a held note: the line's text again in the accent, blurred, lit only on the letters being held, each at its own
 * brightness, so a verse of patter has none and a carried note lights up letter by letter. Laid out at the line's size and given
 * [LyricsMetrics.GLOW_ROOM_DP] of room round it to spread into, without the room taking any space of the row's: a blur is
 * computed on its layer's own bitmap, so a halo with nowhere to go would have a hard edge.
 */
@Composable
private fun Bloom(line: LyricLine, textStyle: TextStyle, layoutRef: LayoutHolder, env: LyricEnv, lifter: WordLifter, glow: State<Float>) {
    val accentInk = remember(env.ink) { ColorProducer { env.ink.accent } }
    Box(
        Modifier.layout { measurable, constraints ->
            val room = LyricsMetrics.GLOW_ROOM_DP.dp.roundToPx()
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * room else constraints.maxWidth,
                    minHeight = 0,
                ),
            )
            layout((placeable.width - 2 * room).coerceAtLeast(0), (placeable.height - 2 * room).coerceAtLeast(0)) { placeable.place(-room, -room) }
        },
    ) {
        BasicText(
            text = line.text,
            style = textStyle,
            color = accentInk,
            modifier = Modifier
                .clearAndSetSemantics { }   // a copy of the line that is already there: a screen reader reads it once
                .graphicsLayer { alpha = glow.value }
                .blur(LyricsMetrics.GLOW_RADIUS_DP.dp, BlurredEdgeTreatment.Unbounded)
                // The mask of each letter is erased into this layer, so it needs one of its own, with the room inside it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .padding(LyricsMetrics.GLOW_ROOM_DP.dp)
                .fillMaxWidth()
                .drawWithContent {
                    val layout = layoutRef.value
                    if (layout != null) lifter.bloom(this, layout, env.clock.longValue)
                },
        )
    }
}

/**
 * Draws the content of the node as far as [fraction] of its text has been sung. A line that wraps fills in reading order: the
 * visual lines above the one holding the edge are drawn whole, that one from its start edge to the edge of the sung part (a
 * right-to-left line from its right edge), the ones below not at all. The edge moves through a character, not from one to the next.
 * This is the fill of a line that is stamped only at its start; a line with word timings is [sweepWords].
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
 * show three dots that light one after the other across it, so a long break reads as time running down. A tap on it seeks, when
 * the song is stamped and no pick is open.
 */
@Composable
private fun GapDots(
    index: Int,
    lines: List<LyricLine>,
    state: State<LineState>,
    clock: MutableLongState,
    ink: LyricInk,
    reduced: Boolean,
    seekable: Boolean,
    onSeek: (Long) -> Unit,
) {
    val line = lines[index]
    val open = animateFloatAsState(if (state.value == LineState.CURRENT) 1f else 0f, CosmoMotion.move(reduced), label = "gapOpen")
    val instrumental = stringResource(Res.string.instrumental)
    val gapTap = if (seekable) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onSeek(line.timeMs) }
    } else {
        Modifier
    }
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
            .then(gapTap)
            .semantics { contentDescription = instrumental }
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
 * While lines are being picked for a card ([share]) the foot of the panel is Cancel and Share instead. [loadingText] stands in while
 * the lookup is still running, [lyrics] being null until it has come back.
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
    share: CosmoLyricShare? = null,
) {
    val lines = lyrics.orEmpty()
    // A pick belongs to the open lyrics: when they close with one under way, it is dropped.
    val latestShare by rememberUpdatedState(share)
    DisposableEffect(Unit) { onDispose { latestShare?.cancel() } }
    Column(modifier) {
        // The motes that rise off the new text when the romanisation or translation is switched, over the list and outside its animation clock.
        LyricsTranslationMotion(
            trigger = translation.transition,
            reduceMotion = translation.reduceMotion,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { particleProgress ->
            CosmoLyricsView(
                lines = lines,
                subLines = translation.subLines,
                trackKey = trackKey,
                playhead = playhead,
                isPlaying = isPlaying,
                onSeekToLine = onSeekToLine,
                style = style,
                modifier = Modifier.fillMaxSize(),
                emptyText = if (lyrics == null && !lyricsUnavailable) loadingText else stringResource(Res.string.lyrics_not_available),
                topPadding = topPadding,
                selection = share?.selection ?: LyricSelection.None,
                particles = particleProgress,
            )
        }
        if (share != null && share.picking) {
            LyricsPickFooter(shareEnabled = share.shareEnabled, onCancel = share::cancel, onShare = share::share)
        } else {
            // While the lookup runs the middle of the panel says so; the footer would say the same line again.
            val looking = lyrics == null && !lyricsUnavailable
            LyricsFooter(
                translation.status.takeUnless { looking && it == loadingText }.orEmpty(), translation, hasLines = lines.isNotEmpty(),
                onOpenProviders = onOpenProviders,
            )
        }
    }
}

/** The line that names the source (or what the translation is doing) and the two toggles; 48 dp tall. */
@Composable
private fun LyricsFooter(status: String, translation: LyricsTranslationUi, hasLines: Boolean, onOpenProviders: () -> Unit) {
    val type = LocalCosmoType.current
    val ink = Color(LocalCosmoColors.current.palette.textSecondary)
    val statusStyle = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 13.sp, fontWeight = FontWeight(600), textDecoration = TextDecoration.Underline) }
    val haptics = rememberHaptics()
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
                    .clickable(role = Role.Button) { haptics.play(Haptic.Select); onOpenProviders() }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        LyricsToggle(
            Icons.Rounded.Language,
            stringResource(if (translation.showingRomanization) Res.string.show_original_lyrics else Res.string.romanize_lyrics),
            translation.romanizationState, translation.showingRomanization, hasLines, translation.toggleRomanization,
        )
        LyricsToggle(
            Icons.Rounded.Translate,
            stringResource(if (translation.showingTranslation) Res.string.show_original_lyrics else Res.string.translate_lyrics),
            translation.translationState, translation.showingTranslation, hasLines, translation.toggleTranslation,
        )
    }
}

/**
 * One of the two toggles that ask for a romanisation or a translation, in the states of the old ones: on while its version is
 * shown or its lookup is running (a spinner takes the icon's place, a still arc with reduced motion), and dimmed, though still
 * tappable (a tap says why), once the service has said the lyrics are already in that language or script.
 */
@Composable
private fun LyricsToggle(
    icon: ImageVector,
    description: String,
    state: LyricsTranslationUiState,
    showing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val loading = state is LyricsTranslationUiState.Loading
    val dimmed = state is LyricsTranslationUiState.SameLanguage
    val reduced = LocalReducedMotion.current
    val ink = Color(LocalCosmoColors.current.palette.textPrimary)
    Box(Modifier.graphicsLayer { alpha = if (dimmed) DIMMED_TOGGLE else 1f }, contentAlignment = Alignment.Center) {
        CosmoIconButton(
            if (loading) BLANK_ICON else icon, description, onClick,
            style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = showing || loading, enabled = enabled,
        )
        if (loading) {
            if (reduced) {
                CircularProgressIndicator(progress = { SPINNER_STILL }, modifier = Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            }
        }
    }
}

/** A toggle whose lyrics are already in the language, or the script, it would turn them into. */
private const val DIMMED_TOGGLE = 0.42f

/** The arc a spinner holds still at with reduced motion. */
private const val SPINNER_STILL = 0.3f

/** No glyph at all: where a toggle's icon is while its spinner is. */
private val BLANK_ICON: ImageVector = ImageVector.Builder(
    name = "blank", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).build()
