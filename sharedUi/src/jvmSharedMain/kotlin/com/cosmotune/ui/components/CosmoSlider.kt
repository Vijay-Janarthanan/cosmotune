package com.cosmotune.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.cosmotune.design.SliderMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The thick slider (B+C "Slider, thick"), for volume and the equaliser: a 16 dp bar in two pieces round a 4 x 44 dp handle with
 * 6 dp gaps. The played piece (corners 8 dp at the bar's end, 3 dp at the handle) is a gradient from `accent` to the halfway
 * colour between `accent` and `buttonGradientEnd`; the unplayed piece (corners 3 and 8 dp) is `textPrimary` at the track
 * opacity, with a 4 dp `accent` stop dot 6 dp from its end; the handle is `textPrimary`.
 *
 * [value] is 0 to 1. The slider is 44 dp tall and has a 48 dp touch area; a tap jumps, a drag follows, the arrow keys, Page
 * Up and Down, Home and End step it (desktop), and screen readers get its [contentDescription], its range and a progress
 * action. [onValueChangeFinished] is called when the pointer lets go (or after every key), so a caller can save then.
 * Colours are read while drawing, so a palette fade redraws the slider without recomposing it.
 */
@Composable
fun CosmoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    contentDescription: String? = null,
) {
    val colors = LocalCosmoColors.current
    val interaction = remember { MutableInteractionSource() }
    val played = remember { Path() }
    val unplayed = remember { Path() }
    val shown = value.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(SliderMetrics.touchHeightDp(SliderMetrics.HEIGHT_DP).dp)
            .scrubKeys({ shown }, onValueChange, onValueChangeFinished)
            .focusable(interactionSource = interaction)
            .cosmoFocusRing(interaction, radius = 12.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { target -> onValueChange(target.coerceIn(0f, 1f)); onValueChangeFinished(); true }
            }
            .scrubGestures(onValueChange, onValueChangeFinished),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val mid = size.height / 2f
            val half = SliderMetrics.HALF_SLOT_DP.dp.toPx()
            val trackH = SliderMetrics.TRACK_DP.dp.toPx()
            val outer = CornerRadius(SliderMetrics.OUTER_RADIUS_DP.dp.toPx())
            val inner = CornerRadius(SliderMetrics.INNER_RADIUS_DP.dp.toPx())
            val centre = SliderMetrics.handleCentre(shown, width, half)
            val activeEnd = SliderMetrics.activeEnd(centre, half)
            val inactiveStart = SliderMetrics.inactiveStart(centre, half)
            val top = mid - trackH / 2f
            val bottom = mid + trackH / 2f

            val accent = colors.live(CoverField.ACCENT)
            if (activeEnd > 0.5f) {
                val end = lerp(accent, colors.live(CoverField.BUTTON_GRADIENT_END), 0.5f)
                played.rewind()
                played.addRoundRect(RoundRect(0f, top, activeEnd, bottom, outer, inner, inner, outer))
                drawPath(played, Brush.horizontalGradient(listOf(accent, end), 0f, activeEnd))
            }

            val ink = colors.live(CoverField.TEXT_PRIMARY)
            if (inactiveStart < width - 0.5f) {
                unplayed.rewind()
                unplayed.addRoundRect(RoundRect(inactiveStart, top, width, bottom, inner, outer, outer, inner))
                drawPath(unplayed, ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)))
                val dot = SliderMetrics.STOP_DOT_DP.dp.toPx()
                val dotX = width - SliderMetrics.STOP_INSET_DP.dp.toPx() - dot / 2f
                if (dotX - dot / 2f > inactiveStart + inner.x) drawCircle(accent, dot / 2f, Offset(dotX, mid))
            }

            val handleW = SliderMetrics.HANDLE_W_DP.dp.toPx()
            val handleH = minOf(SliderMetrics.HANDLE_H_DP.dp.toPx(), size.height)
            drawRoundRect(ink, Offset(centre - handleW / 2f, mid - handleH / 2f), Size(handleW, handleH), CornerRadius(handleW / 2f))
        }
    }
}
