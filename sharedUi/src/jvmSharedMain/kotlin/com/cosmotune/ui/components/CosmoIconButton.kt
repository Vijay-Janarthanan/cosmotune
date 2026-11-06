package com.cosmotune.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The styles the callers ask for. In Orbit every one is the same flat round button ([OrbitRoundButton], the ink at 12 %): PLAIN, TONAL
 * and GLASS (Orbit has no glass) are kept so the callers keep compiling.
 */
enum class CosmoIconButtonStyle { PLAIN, TONAL, GLASS }

/**
 * An icon button in Orbit: [OrbitRoundButton], [size] across, the page's ink at 12 % with the glyph in the ink. With [on] set it is a
 * toggle (toggle haptics, its state told to screen readers) and when on it is solid ink with the ground's colour for its glyph;
 * [accentWhenOn] keeps it tonal and draws it in the accent instead (the liked heart). [tint] replaces the resting ink (a row of quiet
 * tools in the dim ink); an on state keeps its own. A 40 dp button reserves a 48 dp touch target. Disabled at 45 %. The
 * [contentDescription] is read by screen readers and shown as a tooltip under a long press or the pointer. [style] and [floating]
 * are kept for the callers.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CosmoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CosmoIconButtonStyle = CosmoIconButtonStyle.PLAIN,
    size: Dp = 48.dp,
    on: Boolean? = null,
    accentWhenOn: Boolean = false,
    enabled: Boolean = true,
    floating: Boolean = false,
    tint: Color? = null,
) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val haptics = rememberHaptics()
    val isOn = on == true
    val ink = when {
        isOn && accentWhenOn -> look.accent
        isOn -> look.ink
        tint != null -> tint
        else -> look.ink
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = look.ink, contentColor = look.ground) {
                Text(contentDescription, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = look.ground)
            }
        },
        state = rememberTooltipState(),
    ) {
        OrbitRoundButton(
            icon = icon,
            label = contentDescription,
            ink = ink,
            onClick = { haptics.play(if (on == null) Haptic.Tap else if (on) Haptic.ToggleOff else Haptic.ToggleOn); onClick() },
            modifier = modifier
                .minimumInteractiveComponentSize()
                // A toggle is a checkbox to a screen reader, with its state.
                .then(if (on != null) Modifier.semantics { role = Role.Checkbox; toggleableState = ToggleableState(on) } else Modifier),
            size = size,
            on = isOn && !accentWhenOn,
            onInk = look.ground,
            enabled = enabled,
        )
    }
}
