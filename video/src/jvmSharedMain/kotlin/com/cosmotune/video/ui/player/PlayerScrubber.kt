package com.cosmotune.video.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyMap
import com.cosmotune.video.player.ScrubberGeometry
import com.cosmotune.video.player.StoryboardFrame
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_seek_bar
import com.cosmotune.video.settings.VideoSettings
import org.jetbrains.compose.resources.stringResource

/*
 * The minimal scrubber's colours over the video (spec 6.5): rest white 30 %, buffered white 45 %, played `accent`; the dot's halo
 * 4 dp of `accent` at 25 % (the B+C "Scrubber, minimal"). On the full window's glass bar the white becomes ink.
 */
private const val REST_ALPHA = 0.30f
private const val BUFFERED_ALPHA = 0.45f
private const val HALO_DP = 4f
private const val HALO_ALPHA = 0.25f

/** The bubble: the storyboard frame's radius (spec 6.5: 160 x 90, radius 12), 6 dp of glass round it, 8 dp above the line. */
private const val FRAME_RADIUS_DP = 12f
private const val BUBBLE_PAD_DP = 6f
private const val BUBBLE_GAP_DP = 8f
private const val BUBBLE_TIME_SP = 13f
private const val BUBBLE_TIME_WEIGHT = 650
private const val BUBBLE_CHAPTER_SP = 12.5f

/**
 * The minimal scrubber (spec 6.5, the B+C "Scrubber, minimal"): a 3 dp line with a 12 dp dot; rest, buffered and played; the
 * SponsorBlock segments in their category colours when `segmentsOnBar` is on; 2 dp gaps at chapter starts. Dragging (or the arrow
 * keys, 5 s a press) thickens it to 5 dp with a 16 dp dot and shows the C glass bubble with the storyboard frame, the time and the
 * chapter; letting go seeks. A tap seeks too.
 *
 * The band is the 48 dp touch target; the line is at its bottom edge ([lineAtBottom], the page: the player's bottom edge) or its
 * middle. [onGlass] draws it in ink for the full window's bar, whose bubble rises [bubbleLiftDp] more to clear the bar. The playhead
 * is read only while drawing.
 */
@Composable
internal fun PlayerScrubber(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    playhead: Playhead,
    lineAtBottom: Boolean,
    onGlass: Boolean,
    onScrub: (Boolean) -> Unit,
    onInput: () -> Unit,
    modifier: Modifier = Modifier,
    bubbleLiftDp: Float = 0f,
) {
    val duration = state.durationMs
    if (duration <= 0) {
        Box(modifier)
        return
    }
    val colors = LocalCosmoColors.current
    val showSegments = settings.sponsorBlock && settings.segmentsOnBar
    val ranges = remember(state.segments, duration, showSegments) {
        if (showSegments) ScrubberGeometry.ranges(state.segments, duration) else emptyList()
    }
    val chapters = state.details?.chapters.orEmpty()
    val gaps = remember(chapters, duration) { ScrubberGeometry.chapterStarts(chapters, duration).toFloatArray() }
    val buffered = ScrubberGeometry.fraction(state.bufferedMs, duration)
    val drag = remember { ScrubDrag() }
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(Res.string.video_seek_bar)
    val shownSecond = playhead.second.longValue

    fun finish(seek: Boolean) {
        if (!drag.active.value) return
        if (seek) {
            playback.seekTo((drag.fraction.floatValue * duration).toLong())
            playhead.refresh()
        }
        drag.active.value = false
        drag.byKey = false
        onScrub(false)
    }

    Box(
        modifier
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(ScrubberGeometry.fraction(shownSecond * 1000, duration), 0f..1f)
                setProgress { target ->
                    playback.seekTo((target.coerceIn(0f, 1f) * duration).toLong())
                    playhead.refresh()
                    true
                }
            }
            .onKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionLeft -> -PlayerKeyMap.ARROW_SEEK_MS
                    Key.DirectionRight -> PlayerKeyMap.ARROW_SEEK_MS
                    else -> return@onKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!drag.active.value) {
                            drag.fraction.floatValue = ScrubberGeometry.fraction(playhead.now(), duration)
                            drag.active.value = true
                            drag.byKey = true
                            onScrub(true)
                        }
                        drag.fraction.floatValue = (drag.fraction.floatValue + step.toFloat() / duration).coerceIn(0f, 1f)
                        onInput()
                        true
                    }
                    KeyEventType.KeyUp -> { if (drag.byKey) finish(seek = true); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .cosmoFocusRing(interaction, radius = VideoMetrics.SCRUB_DOT_DP.dp, floating = true)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    onInput()
                    playback.seekTo((offset.x / size.width.coerceAtLeast(1) * duration).toLong().coerceIn(0, duration))
                    playhead.refresh()
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        drag.fraction.floatValue = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        drag.active.value = true
                        drag.byKey = false
                        onScrub(true)
                    },
                    onDragEnd = { finish(seek = true) },
                    onDragCancel = { finish(seek = false) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag.fraction.floatValue = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        // Its own layer: the frame ticker repaints this line and nothing around it.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    playhead.frame.longValue   // a new frame: draw again
                    val dragging = drag.active.value
                    val played = if (dragging) drag.fraction.floatValue else ScrubberGeometry.fraction(playback.positionMs(), duration)
                    val ink = if (onGlass) colors.live(CoverField.TEXT_PRIMARY) else Color.White
                    drawScrubLine(
                        played = played,
                        buffered = buffered,
                        dragging = dragging,
                        lineAtBottom = lineAtBottom,
                        rest = ink.copy(alpha = ink.alpha * REST_ALPHA),
                        bufferedColor = ink.copy(alpha = ink.alpha * BUFFERED_ALPHA),
                        accent = colors.live(CoverField.ACCENT),
                        dot = ink,
                        ranges = ranges,
                        gaps = gaps,
                    )
                },
        )
        if (drag.active.value) {
            ScrubBubble(
                drag = drag,
                durationMs = duration,
                state = state,
                storyboard = state.details?.storyboard,
                lineAtBottom = lineAtBottom,
                liftDp = bubbleLiftDp,
            )
        }
    }
}

/** The scrub in progress: whether one is, where it is (0..1), and whether the arrow keys started it (their release seeks). */
@Stable
private class ScrubDrag {
    val active = mutableStateOf(false)
    val fraction = mutableFloatStateOf(0f)
    var byKey = false
}

/**
 * Draws the line in pieces split by the chapter gaps (2 dp each): in each piece the rest, the buffered part, the played part and the
 * segments that fall in it; then the dot with its halo. Nothing is allocated.
 */
private fun DrawScope.drawScrubLine(
    played: Float,
    buffered: Float,
    dragging: Boolean,
    lineAtBottom: Boolean,
    rest: Color,
    bufferedColor: Color,
    accent: Color,
    dot: Color,
    ranges: List<Triple<Float, Float, Long>>,
    gaps: FloatArray,
) {
    val line = (if (dragging) VideoMetrics.SCRUB_LINE_DRAG_DP else VideoMetrics.SCRUB_LINE_DP).dp.toPx()
    val dotSize = (if (dragging) VideoMetrics.SCRUB_DOT_DRAG_DP else VideoMetrics.SCRUB_DOT_DP).dp.toPx()
    val width = size.width
    val centreY = if (lineAtBottom) size.height - line / 2f else size.height / 2f
    val top = centreY - line / 2f
    val corner = CornerRadius(line / 2f)
    val gap = VideoMetrics.CHAPTER_GAP_DP.dp.toPx()
    val playedX = played * width
    val bufferedX = buffered * width
    for (i in 0..gaps.size) {
        val start = if (i == 0) 0f else gaps[i - 1] * width + gap / 2f
        val end = if (i == gaps.size) width else gaps[i] * width - gap / 2f
        if (end <= start) continue
        piece(start, end, top, line, corner, rest)
        if (bufferedX > start) piece(start, minOf(end, bufferedX), top, line, corner, bufferedColor)
        if (playedX > start) piece(start, minOf(end, playedX), top, line, corner, accent)
        for (r in ranges.indices) {
            val range = ranges[r]
            val from = range.first * width
            val to = maxOf(range.second * width, from + line)   // a highlight is a mark as wide as the line
            val a = maxOf(start, from)
            val b = minOf(end, to)
            if (b > a) piece(a, b, top, line, corner, Color(range.third))
        }
    }
    val centre = Offset(playedX.coerceIn(0f, width), centreY)
    drawCircle(accent.copy(alpha = accent.alpha * HALO_ALPHA), radius = dotSize / 2f + HALO_DP.dp.toPx(), center = centre)
    drawCircle(dot, radius = dotSize / 2f, center = centre)
}

private fun DrawScope.piece(start: Float, end: Float, top: Float, line: Float, corner: CornerRadius, color: Color) {
    drawRoundRect(color = color, topLeft = Offset(start, top), size = Size(end - start, line), cornerRadius = corner)
}

/**
 * The bubble over the dragged dot (spec 6.5): C glass, the storyboard frame 160 x 90 (radius 12) when the video has one, the time and
 * the chapter's title. It follows the dot in the layout phase and stays inside the line's width; it recomposes only when the shown
 * second, frame or chapter changes.
 */
@Composable
private fun ScrubBubble(drag: ScrubDrag, durationMs: Long, state: PlaybackUiState, storyboard: Storyboard?, lineAtBottom: Boolean, liftDp: Float) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val chapters = state.details?.chapters.orEmpty()
    val second by remember(durationMs) { derivedStateOf { (drag.fraction.floatValue * durationMs).toLong() / 1000 } }
    val chapter by remember(chapters, durationMs) {
        derivedStateOf { ScrubberGeometry.chapterIndexAt(chapters, (drag.fraction.floatValue * durationMs).toLong()) }
    }
    val frame by remember(storyboard, durationMs) {
        derivedStateOf { storyboard?.let { ScrubberGeometry.storyboardFrame(it, (drag.fraction.floatValue * durationMs).toLong()) } }
    }
    val shape = remember { RoundedCornerShape((FRAME_RADIUS_DP + BUBBLE_PAD_DP).dp) }
    val timeStyle = remember(type) { TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(BUBBLE_TIME_WEIGHT), fontSize = BUBBLE_TIME_SP.sp).tabular() }
    val chapterStyle = remember(type) { TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Medium, fontSize = BUBBLE_CHAPTER_SP.sp) }
    Column(
        Modifier
            .layout { measurable, constraints ->
                // The band is 48 dp tall: the bubble is measured free of it and stands above the line.
                val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth))
                val width = constraints.maxWidth
                val lineY = if (lineAtBottom) constraints.maxHeight else constraints.maxHeight / 2
                layout(width, 0) {
                    val x = (drag.fraction.floatValue * width - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                    placeable.place(x, lineY - placeable.height - (BUBBLE_GAP_DP + liftDp).dp.roundToPx())
                }
            }
            .cosmoGlass(hazeState = null, shape = shape, overVideo = true)
            .padding(BUBBLE_PAD_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val board = storyboard
        val shownFrame = frame
        if (board != null && shownFrame != null) StoryboardImage(board, shownFrame)
        Text(ScrubberGeometry.timeLabel(second * 1000), style = timeStyle, color = Color(palette.textPrimary), maxLines = 1)
        chapters.getOrNull(chapter)?.let {
            Text(
                it.title,
                style = type.forText(chapterStyle, it.title),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = VideoMetrics.STORYBOARD_W_DP.dp),
            )
        }
    }
}

/**
 * One storyboard frame (spec 6.5: 160 x 90, radius 12), cropped from its sheet image: one Coil request per sheet at the sheet's own
 * size (so the frames stay sharp), the sheet drawn scaled and moved so the frame fills the box.
 */
@Composable
private fun StoryboardImage(board: Storyboard, frame: StoryboardFrame) {
    val url = board.urls.getOrNull(frame.sheet) ?: return
    val context = LocalPlatformContext.current
    val request = remember(url, context) { ImageRequest.Builder(context).data(url).size(coil3.size.Size.ORIGINAL).build() }
    val painter = rememberAsyncImagePainter(request)
    Box(
        Modifier
            .size(VideoMetrics.STORYBOARD_W_DP.dp, VideoMetrics.STORYBOARD_H_DP.dp)
            .clip(RoundedCornerShape(FRAME_RADIUS_DP.dp))
            .drawBehind {
                val sheet = painter.intrinsicSize
                if (sheet.isUnspecified || sheet.width <= 0f || board.frameWidth <= 0 || board.frameHeight <= 0) return@drawBehind
                val sx = size.width / board.frameWidth
                val sy = size.height / board.frameHeight
                translate(-frame.column * size.width, -frame.row * size.height) {
                    with(painter) { draw(Size(sheet.width * sx, sheet.height * sy)) }
                }
            },
    )
}
