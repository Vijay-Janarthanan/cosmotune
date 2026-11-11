package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.lyrics.LyricBacking
import com.cosmotune.lyrics.LyricDuet
import com.cosmotune.lyrics.SungFraction
import com.cosmotune.lyrics.styles.LyricCursor
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** The two singers' bubbles and the backing voice, in the cover's colours. */
internal class DuetInk(val leadFill: Color, val leadText: Color, val answerFill: Color, val answerText: Color, val backing: Color)

internal fun duetInkOf(ink: StageInk): DuetInk = DuetInk(
    leadFill = ink.accent.copy(alpha = if (ink.dark) 0.26f else 0.18f),
    leadText = ink.text,
    answerFill = ink.text,
    answerText = ink.ground,
    backing = ink.accent,
)

/**
 * Call & Answer (board 04): each singer on their own side and in their own colour, from the duet data the lines carry; backing
 * vocals float between them in italic serif, quieter, filling on their own timing. The line being sung is whole and filling; the
 * rest step back. A list that follows the song like the list does (the line being sung a third of the way down), with a tap on a
 * bubble seeking to it. Turned on by itself for a song with duet or backing-vocal timing ([com.cosmotune.lyrics.styles.LyricStyles.resolve]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CallAndAnswerStage(
    lines: List<LyricLine>,
    subLines: List<LyricLine>?,
    cursor: StageCursor,
    onSeek: (Long) -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val colors = LocalCosmoColors.current
    val ink = remember(colors.palette, colors.dark) { stageInkOf(colors.palette, colors.dark) }
    val duetInk = remember(ink) { duetInkOf(ink) }
    val fonts = rememberLyricStageFonts()
    val duet = remember(lines) { LyricDuet.isDuet(lines) }
    val clock = cursor.clock
    val current = remember(lines, clock) { derivedStateOf { LyricCursor.current(lines, clock.longValue) } }
    // Only the lines with words: a break is the room between two bubbles.
    val rows = remember(lines) { lines.indices.filter { !lines[it].isGap && lines[it].text.isNotBlank() } }
    val listState = rememberLazyListState()
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(rows, reduced) {
        snapshotFlow { current.value }.collectLatest { index ->
            val row = rows.indexOfLast { it <= index }.coerceAtLeast(0)
            val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            val offset = LyricsMetrics.followOffsetPx(viewport)
            if (!placed || reduced) { listState.scrollToItem(row, offset); placed = true } else listState.animateScrollToItem(row, offset)
        }
    }
    val density = LocalDensity.current
    val bubbleStyle = remember(fonts, density) { TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(700), fontSize = 19.sp, lineHeight = 25.sp) }
    val subStyle = remember(fonts) { TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 17.sp) }
    val backingStyle = remember { TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 26.sp) }
    val viewportPx by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val bottom = with(density) { LyricsMetrics.bottomRoomPx(viewportPx).toDp() }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 12.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(rows) { _, index ->
            val line = lines[index]
            val state = remember(index, current) {
                derivedStateOf {
                    val at = current.value
                    when {
                        at == index -> LyricsMetrics.LineState.CURRENT
                        at > index -> LyricsMetrics.LineState.PAST
                        else -> LyricsMetrics.LineState.UPCOMING
                    }
                }
            }
            val now = state.value
            val shown = animateFloatAsState(
                when (now) { LyricsMetrics.LineState.CURRENT -> 1f; LyricsMetrics.LineState.UPCOMING -> 0.42f; else -> 0.3f },
                CosmoMotion.effects(reduced), label = "duetAlpha",
            )
            val scale = animateFloatAsState(if (reduced || now == LyricsMetrics.LineState.CURRENT) 1f else 0.97f, CosmoMotion.slow(reduced), label = "duetScale")
            val answer = LyricDuet.onEnd(duet, line)
            val fill = if (answer) duetInk.answerFill else duetInk.leadFill
            val text = if (answer) duetInk.answerText else duetInk.leadText
            val sub = subLines?.getOrNull(index)?.text?.takeIf { it.isNotBlank() && it != line.text }
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = shown.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
                horizontalAlignment = if (answer) Alignment.End else Alignment.Start,
            ) {
                Bubble(line, index, lines, text, fill, bubbleStyle, sub, subStyle, now == LyricsMetrics.LineState.CURRENT, clock, onSeek, onLongPress)
                val backing = remember(line) { line.background?.let { LyricBacking.display(it) } }
                if (backing != null) {
                    BackingLine(line.background!!, backing, duetInk.backing, backingStyle, clock, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    line: LyricLine,
    index: Int,
    lines: List<LyricLine>,
    textColor: Color,
    fill: Color,
    style: TextStyle,
    sub: String?,
    subStyle: TextStyle,
    current: Boolean,
    clock: androidx.compose.runtime.LongState,
    onSeek: (Long) -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val layoutRef = remember { LayoutHolder() }
    val dim = remember(textColor) { ColorProducer { textColor.copy(alpha = 0.5f) } }
    val full = remember(textColor) { ColorProducer { textColor } }
    val wordSynced = line.isWordSynced
    Column(
        Modifier
            .widthIn(max = BUBBLE_MAX)
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                onLongClick = onLongPress, onClick = { onSeek(line.timeMs) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box {
            BasicText(line.text, style = style, color = if (current) dim else full, onTextLayout = { layoutRef.value = it })
            if (current) {
                BasicText(
                    line.text, style = style, color = full,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { compositingStrategy = if (wordSynced) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                        .drawWithCache {
                            val feather = if (wordSynced) featherEdge() else null
                            onDrawWithContent {
                                if (wordSynced) sweepWords(layoutRef.value, line, clock.longValue, feather)
                                else sweepFill(layoutRef.value, SungFraction.of(lines, index, clock.longValue))
                            }
                        },
                )
            }
        }
        if (sub != null) BasicText(sub, style = subStyle, color = remember(textColor) { ColorProducer { textColor.copy(alpha = 0.72f) } }, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The backing voice between the bubbles: italic serif in the cover's colour, filling on its own words while it is due. */
@Composable
private fun BackingLine(raw: LyricLine, shown: LyricLine, color: Color, style: TextStyle, clock: androidx.compose.runtime.LongState, modifier: Modifier) {
    val layoutRef = remember { LayoutHolder() }
    val dim = remember(color) { ColorProducer { color.copy(alpha = 0.4f) } }
    val full = remember(color) { ColorProducer { color.copy(alpha = 0.9f) } }
    val timed = raw.isWordSynced
    Box(modifier) {
        BasicText(shown.text, style = style, color = dim, onTextLayout = { layoutRef.value = it })
        BasicText(
            shown.text, style = style, color = full,
            modifier = Modifier
                .clearAndSetSemantics { }
                .graphicsLayer { compositingStrategy = if (timed) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawWithCache {
                    val feather = if (timed) featherEdge() else null
                    onDrawWithContent {
                        val position = clock.longValue
                        if (timed) sweepWords(layoutRef.value, shown, position, feather)
                        else sweepFill(layoutRef.value, if (position >= raw.timeMs) 1f else 0f)
                    }
                },
        )
    }
}

/** A bubble never runs the width of the panel: each voice keeps a margin on the other's side (the board's 78 %). */
private val BUBBLE_MAX = 320.dp
