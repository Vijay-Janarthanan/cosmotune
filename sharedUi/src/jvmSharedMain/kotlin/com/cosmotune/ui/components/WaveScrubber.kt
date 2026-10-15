package com.cosmotune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.SliderMetrics
import com.cosmotune.design.Wave
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_seek_bar
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.roundToInt

/** What the scrubber reuses between frames: a point buffer, one [Path] and the played stretch's gradient. Plain fields, not state. */
private class WaveScratch {
    var points = FloatArray(0)
    val path = Path()
    var brush: Brush? = null
    var brushStart = 0
    var brushEnd = 0
    var brushWidth = -1f
    var stroke: Stroke? = null
    var strokeWidth = -1f
}

private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * The progress bar of Now Playing (B+C "Scrubber, wavy"): a 4 dp round-capped wave of amplitude 3.2 dp and wavelength 26 dp,
 * in the gradient `accent` to `buttonGradientEnd`, up to the handle (4 x 24 dp, `textPrimary`); the rest is a straight track.
 *
 * - [fraction] is read only while drawing, so the position moving does not recompose anything. The wave itself moves at about
 *   3.6 rad/s while [playing] (the phase is one `Float` state read in the draw lambda); paused, the amplitude eases to zero
 *   (a straight line, 231 ms), and with reduced motion the wave **stands still** (phase frozen, 150 ms fade) as on the design
 *   board. The frame loop is a coroutine of this composable, so it stops when paused, with reduced motion and when the
 *   scrubber leaves the screen.
 * - No allocation per frame: one point buffer sized once per width, one [Path] rewound each draw, one gradient made again only
 *   when its colours or the width change (while a palette fade runs that is a brush a frame, like the rest of the ground).
 * - [onScrub] gets the fraction while the pointer moves (a tap, a drag, a key); [onRelease] when it lets go: the caller seeks then.
 * - The touch area is 48 dp tall at least, whatever [height] draws. The scrubber has a name, a range and a progress action
 *   for screen readers, and takes the arrow keys, Page Up and Down, Home and End on the desktop, with a visible focus ring.
 */
@Composable
fun WaveScrubber(
    fraction: () -> Float,
    playing: Boolean,
    onScrub: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = Wave.HEIGHT_DP.dp,
) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val amplitude = remember { Animatable(0f) }
    LaunchedEffect(playing, reduced) { amplitude.animateTo(if (playing) 1f else 0f, CosmoMotion.effects(reduced)) }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduced) {
        if (!playing || reduced) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                phase.floatValue = (phase.floatValue + dt * Wave.PHASE_RAD_PER_S) % TWO_PI
            }
        }
    }
    val scratch = remember { WaveScratch() }
    val label = stringResource(Res.string.cosmo_seek_bar)

    // The position for screen readers, in steps of five percent: the semantics change twenty times over a song, not every frame.
    var announced by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { snapshotFlow { (fraction().coerceIn(0f, 1f) * 20).roundToInt() }.collect { announced = it } }
    val announcedNow = announced

    val interaction = remember { MutableInteractionSource() }
    val touchHeight = SliderMetrics.touchHeightDp(height.value).dp
    Box(
        modifier
            .fillMaxWidth()
            .height(touchHeight)
            .scrubKeys({ fraction() }, onScrub, onRelease)
            .focusable(interactionSource = interaction)
            .cosmoFocusRing(interaction, radius = 12.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(announcedNow / 20f, 0f..1f)
                setProgress { target -> onScrub(target.coerceIn(0f, 1f)); onRelease(); true }
            }
            .scrubGestures(onScrub, onRelease),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val mid = size.height / 2f
            val scale = height.toPx() / Wave.HEIGHT_DP.dp.toPx()
            val step = Wave.STEP_DP.dp.toPx()
            val needed = 2 * Wave.pointCapacity(width, step)
            if (scratch.points.size < needed) scratch.points = FloatArray(needed)   // once per width, not per frame
            val p = fraction().coerceIn(0f, 1f)
            val stroke = Wave.STROKE_DP.dp.toPx()
            val capInset = 2.dp.toPx()

            val count = Wave.playedPoints(
                width, p, Wave.AMPLITUDE_DP.dp.toPx() * amplitude.value, Wave.WAVELENGTH_DP.dp.toPx(), phase.floatValue,
                mid, capInset, Wave.GAP_BEFORE_DP.dp.toPx(), step, scratch.points,
            )
            if (count > 1) {
                val path = scratch.path
                path.rewind()
                path.moveTo(scratch.points[0], scratch.points[1])
                for (i in 1 until count) path.lineTo(scratch.points[2 * i], scratch.points[2 * i + 1])
                val accent = colors.live(CoverField.ACCENT)
                val end = colors.live(CoverField.BUTTON_GRADIENT_END)
                val a = accent.toArgb()
                val b = end.toArgb()
                val brush = scratch.brush
                if (brush == null || scratch.brushStart != a || scratch.brushEnd != b || scratch.brushWidth != width) {
                    scratch.brush = Brush.horizontalGradient(listOf(accent, end), 0f, width)
                    scratch.brushStart = a; scratch.brushEnd = b; scratch.brushWidth = width
                }
                if (scratch.stroke == null || scratch.strokeWidth != stroke) {
                    scratch.stroke = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    scratch.strokeWidth = stroke
                }
                drawPath(path, scratch.brush!!, style = scratch.stroke!!)
            }
            val ink = colors.live(CoverField.TEXT_PRIMARY)
            val trackStart = p * width + Wave.GAP_AFTER_DP.dp.toPx()
            val trackEnd = width - capInset
            if (trackStart < trackEnd) {
                drawLine(ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)), Offset(trackStart, mid), Offset(trackEnd, mid), stroke, StrokeCap.Round)
            }
            val handleW = Wave.HANDLE_W_DP.dp.toPx()
            val handleH = Wave.HANDLE_H_DP.dp.toPx() * scale
            val handleX = (p * width).coerceIn(handleW / 2f, maxOf(handleW / 2f, width - handleW / 2f))
            drawRoundRect(ink, Offset(handleX - handleW / 2f, mid - handleH / 2f), Size(handleW, handleH), CornerRadius(handleW / 2f))
        }
    }
}
