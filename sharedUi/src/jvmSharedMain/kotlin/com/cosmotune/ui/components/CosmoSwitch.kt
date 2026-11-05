package com.cosmotune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

/** The check mark of the on thumb, `M7 12.5l3.2 3.2L17 9` on a 24 unit grid (components-v3bc.css, `.sw2.is-on::after`). */
private val CHECK_X = floatArrayOf(7f, 10.2f, 17f)
private val CHECK_Y = floatArrayOf(12.5f, 15.7f, 9f)
private const val GRID = 24f

/**
 * The toggle of the B+C system: a 52 x 32 dp pill. Off it is a faint ink wash with a 1.5 dp `textTertiary` ring and a 16 dp
 * thumb in `textTertiary`; on it is the primary cover gradient with a 24 dp `onAccent` thumb that carries a check. The thumb
 * travels and grows between the two on the move spring (a 150 ms fade under reduced motion) and the track cross-fades with
 * it. On a floating layer ([floating]) the on track is the C primary (cover tint over the glass, a 1.5 dp gradient border) and
 * the focus ring is ink. `Role.Switch`, toggle haptics, a 48 dp touch target, 38 % when [enabled] is false. The label belongs
 * to the row that holds the switch.
 */
@Composable
fun CosmoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    floating: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, reduced) { progress.animateTo(if (checked) 1f else 0f, CosmoMotion.move(reduced)) }
    val thumb = remember { FloatArray(3) }
    val check = remember { Path() }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(ControlMetrics.SWITCH_WIDTH_DP.dp, ControlMetrics.SWITCH_HEIGHT_DP.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .cosmoFocusRing(interaction, (ControlMetrics.SWITCH_HEIGHT_DP / 2f).dp, floating)
            .drawBehind {
                keepRedrawnWith(palette)
                val p = progress.value
                val on = p.coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f)
                // the track: the off look fades out as the primary gradient fades in
                if (on < 1f) {
                    drawRoundRect(colors.ink(ControlMetrics.SWITCH_TRACK_FILL), cornerRadius = radius, alpha = 1f - on)
                    val ring = ControlMetrics.SWITCH_RING_DP.dp.toPx()
                    drawRoundRect(
                        colors.live(CoverField.TEXT_TERTIARY), Offset(ring / 2f, ring / 2f), Size(size.width - ring, size.height - ring),
                        CornerRadius((size.height - ring) / 2f), Stroke(ring), alpha = 1f - on,
                    )
                }
                if (on > 0f) {
                    // on a floating layer the track is the C primary: cover tint over the glass, with its gradient border
                    drawRoundRect(if (floating) floatingPrimaryFill(colors) else primaryFill(colors), cornerRadius = radius, alpha = on)
                    if (floating) {
                        val border = ControlMetrics.C_PRIMARY_BORDER_DP.dp.toPx()
                        drawRoundRect(
                            floatingBorder(colors), Offset(border / 2f, border / 2f), Size(size.width - border, size.height - border),
                            CornerRadius((size.height - border) / 2f), alpha = on, style = Stroke(border),
                        )
                    }
                }
                // the thumb: 16 dp at (8, 8) off, 24 dp at (24, 4) on
                ControlMetrics.switchThumb(p, thumb)
                val diameter = thumb[0].dp.toPx()
                val centre = Offset(thumb[1].dp.toPx() + diameter / 2f, thumb[2].dp.toPx() + diameter / 2f)
                drawCircle(lerp(colors.live(CoverField.TEXT_TERTIARY), colors.live(CoverField.ON_ACCENT), on), diameter / 2f, centre)
                if (on > 0.02f) {
                    // a check in the track's colour: the engine keeps `accent` readable on `onAccent`
                    val unit = ControlMetrics.SWITCH_CHECK_DP.dp.toPx() / GRID
                    check.reset()
                    for (i in CHECK_X.indices) {
                        val x = centre.x + (CHECK_X[i] - GRID / 2f) * unit
                        val y = centre.y + (CHECK_Y[i] - GRID / 2f) * unit
                        if (i == 0) check.moveTo(x, y) else check.lineTo(x, y)
                    }
                    drawPath(
                        check, colors.live(CoverField.ACCENT).copy(alpha = on),
                        style = Stroke(ControlMetrics.SWITCH_CHECK_STROKE_UNITS * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            .hoverable(interaction, enabled)
            .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch) {
                haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                onCheckedChange(it)
            },
    )
}
