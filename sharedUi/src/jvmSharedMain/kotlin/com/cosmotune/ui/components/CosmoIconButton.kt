package com.cosmotune.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * PLAIN has no fill at rest (a transport button); TONAL rests on a soft `surfaceVariant` gradient; GLASS is the floating
 * look: a glass circle, the same as `floating = true` on a TONAL one.
 */
enum class CosmoIconButtonStyle { PLAIN, TONAL, GLASS }

/**
 * An icon button of the B+C system. In the page it is a rounded square (0.30 of its size) that squares up to 0.20 and
 * shrinks to 0.94 when pressed, and turns round when [on]; on a floating layer ([floating], or the GLASS style) it is a glass
 * circle that only scales to 0.94 when pressed, and when [on] takes the C primary tint and a 1.5 dp ring.
 *
 * With [on] set it is a toggle (`Role.Checkbox`, toggle haptics) and in the page its fill is the primary gradient with
 * `onAccent` ink (PLAIN: the tonal container with `onAccentContainer`). [accentWhenOn] keeps the tonal container and draws the
 * icon in `accent` instead, the liked heart. A 40 dp button reserves a 48 dp touch target. The icon is 20 / 24 / 28 dp at
 * 40 / 48 / 56. A disabled button fades to 38 %. [contentDescription] is read by screen readers.
 */
@Composable
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
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val haptics = rememberHaptics()
    val isFloating = floating || style == CosmoIconButtonStyle.GLASS
    val isOn = on == true
    val ink = Color(
        when {
            isFloating -> palette.textPrimary
            !isOn -> palette.textPrimary
            accentWhenOn -> palette.accent
            style == CosmoIconButtonStyle.TONAL -> palette.onAccent
            else -> palette.onAccentContainer
        },
    )
    MorphBox(
        targetFor = { pressed -> ControlMorph.iconButton(size.value, isOn, pressed, isFloating) },
        onClick = { haptics.play(if (on == null) Haptic.Tap else if (on) Haptic.ToggleOff else Haptic.ToggleOn); onClick() },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        toggled = on,
        role = if (on != null) Role.Checkbox else Role.Button,
        floating = isFloating,
        stateLayer = ink,
        edge = { outline -> iconEdge(outline, style, isFloating, isOn, colors) },
        fill = { iconButtonFill(style, isFloating, isOn, accentWhenOn, colors) },
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.iconButtonIconDp(size.value).dp))
    }
}

private fun DrawScope.iconButtonFill(style: CosmoIconButtonStyle, floating: Boolean, on: Boolean, accentWhenOn: Boolean, c: CosmoColors): Brush? = when {
    floating -> if (on) floatingPrimaryFill(c) else if (style == CosmoIconButtonStyle.PLAIN) null else c.glassFill()
    on && accentWhenOn -> tonalFill(c)
    on -> if (style == CosmoIconButtonStyle.TONAL) primaryFill(c) else tonalFill(c)
    style == CosmoIconButtonStyle.TONAL -> iconFill(c)
    else -> null
}

private fun DrawScope.iconEdge(outline: Path, style: CosmoIconButtonStyle, floating: Boolean, on: Boolean, c: CosmoColors) {
    if (!floating) return
    if (on) innerRing(outline, c.live(CoverField.BUTTON_GRADIENT_START), ControlMetrics.C_PRIMARY_BORDER_DP)
    else if (style != CosmoIconButtonStyle.PLAIN) glassEdge(outline, c.dark)
}
