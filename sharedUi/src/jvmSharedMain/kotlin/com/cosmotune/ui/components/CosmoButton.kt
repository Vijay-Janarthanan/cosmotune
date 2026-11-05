package com.cosmotune.ui.components

import com.cosmotune.theme.CoverField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

/** PRIMARY is the accent, TONAL `surface2`, QUIET a faint wash of ink, TEXT no fill at all (DESIGN_SYSTEM.md 4.12). */
enum class CosmoButtonKind { PRIMARY, TONAL, QUIET, TEXT }

/** 40, 48 and 56 dp tall (`ControlMetrics.BUTTON_*`). */
enum class CosmoButtonSize(val heightDp: Float) {
    SMALL(ControlMetrics.BUTTON_SMALL_DP),
    MEDIUM(ControlMetrics.BUTTON_MEDIUM_DP),
    LARGE(ControlMetrics.BUTTON_LARGE_DP),
}

/**
 * A button (DESIGN_SYSTEM.md 3.4): a pill that keeps its shape when pressed. In the page ([floating] false) PRIMARY is the solid
 * accent and TONAL `surface2`. On a floating layer ([floating] true) it has a glass tint, an ink label, and for PRIMARY a
 * 1.5 dp gradient border; it scales to 0.97 when pressed. QUIET takes the glass look of a secondary floating button, TEXT the
 * underlined ink of a tertiary one. Reduced motion: no scale, 150 ms fades.
 *
 * A SMALL button is 40 dp tall but reserves a 48 dp touch target (`minimumInteractiveComponentSize`). The label is also the
 * content description. The colours are resolved from the current palette every time the button draws.
 */
@Composable
fun CosmoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CosmoButtonKind = CosmoButtonKind.PRIMARY,
    size: CosmoButtonSize = CosmoButtonSize.MEDIUM,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    floating: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val haptics = rememberHaptics()
    val height = size.heightDp
    val ink = Color(buttonInk(palette, kind, floating)).let { if (enabled) it else Color(palette.textPrimary).copy(alpha = DISABLED_ALPHA) }
    val padding = if (kind == CosmoButtonKind.TEXT) ControlMetrics.TEXT_BUTTON_PADDING_DP else ControlMetrics.buttonPaddingDp(height)
    val pressedLayer = if (floating && kind == CosmoButtonKind.PRIMARY) ControlMetrics.C_PRIMARY_PRESSED_LAYER else STATE_LAYER_PRESSED
    MorphBox(
        targetFor = { pressed -> ControlMorph.primary(height, pressed, floating) },
        onClick = { haptics.play(Haptic.Tap); onClick() },
        modifier = modifier.minimumInteractiveComponentSize().height(height.dp),
        enabled = enabled,
        floating = floating,
        dimWhenDisabled = false,
        stateLayer = if (floating && kind == CosmoButtonKind.PRIMARY) Color(palette.buttonGradientEnd) else ink,
        pressedLayerAlpha = pressedLayer,
        edge = { outline -> buttonEdge(outline, kind, floating, enabled, colors) },
        fill = { buttonFill(kind, floating, enabled, colors) },
    ) {
        Row(
            Modifier.padding(horizontal = padding.dp),
            horizontalArrangement = Arrangement.spacedBy(ControlMetrics.BUTTON_GAP_DP.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.buttonIconDp(height).dp))
            val type = LocalCosmoType.current
            Text(
                text = text,
                color = ink,
                style = type.forText(type.button.copy(fontSize = ControlMetrics.buttonFontSp(height).sp), text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // C tertiary: ink, underlined. (Compose cannot set the underline's offset or thickness; the spec's 4 dp / 1.5 dp are approximated.)
                textDecoration = if (floating && kind == CosmoButtonKind.TEXT) TextDecoration.Underline else null,
            )
        }
    }
}

/** The colour of the label: the button's own ink in the page, `textPrimary` (ink) on a floating layer. */
private fun buttonInk(palette: CoverColors, kind: CosmoButtonKind, floating: Boolean): Int = when {
    floating -> palette.textPrimary
    kind == CosmoButtonKind.PRIMARY -> palette.onAccent
    kind == CosmoButtonKind.TONAL -> palette.textPrimary
    kind == CosmoButtonKind.QUIET -> palette.textPrimary
    else -> palette.textSecondary
}

private fun DrawScope.buttonFill(kind: CosmoButtonKind, floating: Boolean, enabled: Boolean, c: CosmoColors): Brush? = when {
    kind == CosmoButtonKind.TEXT -> null
    !enabled -> SolidColor(c.ink(0.12f))   // `textPrimary` at 12 %, label at 38 %
    floating -> if (kind == CosmoButtonKind.PRIMARY) floatingPrimaryFill(c) else c.glassFill()
    kind == CosmoButtonKind.PRIMARY -> SolidColor(c.live(CoverField.ACCENT))
    kind == CosmoButtonKind.TONAL -> SolidColor(c.live(CoverField.SURFACE_HIGH))
    else -> SolidColor(c.ink(ControlMetrics.QUIET_FILL))
}

private fun DrawScope.buttonEdge(outline: Path, kind: CosmoButtonKind, floating: Boolean, enabled: Boolean, c: CosmoColors) {
    if (!enabled || kind == CosmoButtonKind.TEXT) return
    val dark = c.dark
    when {
        floating && kind == CosmoButtonKind.PRIMARY -> {
            innerRing(outline, floatingBorder(c), ControlMetrics.C_PRIMARY_BORDER_DP)
            topLine(outline, Color.White.copy(alpha = ControlMetrics.C_PRIMARY_TOP_HIGHLIGHT))
        }
        floating -> glassEdge(outline, dark, ControlMetrics.glassRingSecondary(dark))
        kind == CosmoButtonKind.PRIMARY -> topLine(outline, Color.White.copy(alpha = ControlMetrics.highlightPrimary(dark)))
        else -> topLine(outline, Color.White.copy(alpha = ControlMetrics.HIGHLIGHT_TONAL))
    }
}
