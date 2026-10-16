package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_seek_bar
import org.jetbrains.compose.resources.stringResource

/*
 * The pager's progress line (spec 5.15). White over the video is the documented media exception.
 */

/** The played part: white at 90 %. */
private val PLAYED = Color.White.copy(alpha = 0.90f)

/** The rest: white at 30 %. */
private val REST = Color.White.copy(alpha = 0.30f)

/** The band that takes the drag: the line lies along its bottom edge. It stops short of the bottom block (28 dp above the inset). */
internal val PROGRESS_BAND = 24.dp

/** No drag under way. */
private const val NOT_DRAGGING = -1f

/**
 * The progress line of a Short (spec 5.15): [VideoMetrics.PAGER_PROGRESS_DP] across the bottom of its band, the played part white at
 * 90 %, the rest white at 30 %, no dot. The playhead is read from [VideoPlayback.positionMs] while drawing: a frame ticker runs while
 * [active] and [playing] and only bumps a number the draw lambda reads, so nothing recomposes per frame and nothing is allocated per
 * frame. Dragging along the band (when [active]) moves the line, [VideoMetrics.PAGER_PROGRESS_DRAG_DP] thick, and seeks where it is
 * let go; [durationMs] is the Short's. Not [active] (a page that is not the settled one), it shows nothing played.
 */
@Composable
internal fun ShortsProgressLine(playback: VideoPlayback, durationMs: Long, playing: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val frame = remember { mutableLongStateOf(0L) }
    val drag = remember { mutableFloatStateOf(NOT_DRAGGING) }
    val duration by rememberUpdatedState(durationMs)
    // One lambda for every frame: the loop allocates nothing.
    val onFrame: (Long) -> Unit = remember(frame) { { nanos -> frame.longValue = nanos } }
    LaunchedEffect(active, playing) {
        frame.longValue += 1                      // draw once now: a seek while paused, a page that settled
        if (active && playing) while (true) withFrameNanos(onFrame)
    }
    val label = stringResource(Res.string.video_seek_bar)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .fillMaxWidth()
            .height(PROGRESS_BAND)
            .semantics { contentDescription = label }
            .pointerInput(playback, active, rtl) {
                if (!active) return@pointerInput
                fun fractionAt(x: Float): Float {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    return if (rtl) 1f - f else f
                }
                detectHorizontalDragGestures(
                    onDragStart = { start -> drag.floatValue = fractionAt(start.x) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag.floatValue = fractionAt(change.position.x)
                    },
                    onDragEnd = {
                        val to = drag.floatValue
                        drag.floatValue = NOT_DRAGGING
                        if (to >= 0f && duration > 0) playback.seekTo((to * duration).toLong())
                        frame.longValue += 1
                    },
                    onDragCancel = { drag.floatValue = NOT_DRAGGING },
                )
            }
            .drawBehind {
                frame.longValue                       // read, so each tick draws again
                val dragged = drag.floatValue
                val dragging = dragged >= 0f
                val thickness = (if (dragging) VideoMetrics.PAGER_PROGRESS_DRAG_DP else VideoMetrics.PAGER_PROGRESS_DP).dp.toPx()
                val total = duration
                val fraction = when {
                    dragging -> dragged
                    !active || total <= 0L -> 0f
                    else -> (playback.positionMs().toFloat() / total.toFloat()).coerceIn(0f, 1f)
                }
                val top = size.height - thickness
                drawRect(REST, topLeft = Offset(0f, top), size = Size(size.width, thickness))
                val played = size.width * fraction
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - played else 0f
                drawRect(PLAYED, topLeft = Offset(left, top), size = Size(played, thickness))
            },
    )
}
