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

/** Orbit's switch (board 2, `.tog`): a 46 by 28 dp pill with a 22 dp knob 3 dp in from its ends. */
private const val TRACK_WIDTH_DP = 46f
private const val TRACK_HEIGHT_DP = 28f
private const val KNOB_DP = 22f
private const val KNOB_INSET_DP = 3f

/**
 * The toggle in Orbit (board 2, `.tog`): a 46 by 28 dp pill. Off it is the ink at 18 % with a pale knob at the start; on it is the
 * accent (peach in the dark, its deeper step in the light) with a dark knob at the end. The knob travels on the move spring (a 150 ms
 * fade under reduced motion) and the track cross-fades with it. `Role.Switch`, toggle haptics, a 48 dp touch target, 38 % when
 * [enabled] is false. [floating] is kept for its callers: Orbit has no glass, so a floating switch is the same switch. The label
 * belongs to the row that holds the switch.
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
    val dark = colors.dark
    val accent = if (dark) ORBIT_PEACH else ORBIT_PEACH_INK
    val knobOn = if (dark) Color(0xFF2A100A) else Color.White
    val knobOff = if (dark) Color(0xFFE3D0C6) else Color.White
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(TRACK_WIDTH_DP.dp, TRACK_HEIGHT_DP.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .cosmoFocusRing(interaction, (TRACK_HEIGHT_DP / 2f).dp, floating)
            .drawBehind {
                keepRedrawnWith(palette)
                val on = progress.value.coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(lerp(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = 0.18f), accent, on), cornerRadius = radius)
                val knob = KNOB_DP.dp.toPx()
                val inset = KNOB_INSET_DP.dp.toPx()
                val x = inset + (size.width - knob - inset * 2) * progress.value
                drawCircle(lerp(knobOff, knobOn, on), knob / 2f, Offset(x + knob / 2f, size.height / 2f))
            }
            .hoverable(interaction, enabled)
            .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch) {
                haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                onCheckedChange(it)
            },
    )
}

/** Orbit's peach ("playing now", and a switch that is on) in the dark, and its deeper step in the light. */
private val ORBIT_PEACH = Color(0xFFFFB98F)
private val ORBIT_PEACH_INK = Color(0xFFC9663A)
