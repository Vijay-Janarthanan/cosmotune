package com.cosmotune.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ColorMix
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.CssGradient
import com.cosmotune.design.GlassPolicy
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoColors

/*
 * The colours of the morphing controls, as the B+C spec writes them (`v3/01-components-spec-bc.md`, `components-v3bc.css`).
 *
 * Every function here resolves its colours from the CURRENT palette (`CosmoColors.live`), so it must be called from a draw
 * lambda each time the control draws: a brush made once at theme creation makes every control the fallback apricot (the
 * designer's round-1 bug, and handoff pitfall 1). Nothing here is remembered.
 *
 * B is the look inside the page (soft cover gradients, no glow); C is the look of a control on a floating layer (glass and
 * ink). C draws a translucent tint and takes the blurred surface behind it from whatever it sits on: it never blurs itself.
 */

/** Stops per gradient: OKLab-interpolated, so a gradient of two colours reads as the CSS `in oklab` one (Compose mixes in sRGB). */
private const val GRADIENT_STOPS = 5

private val WHITE = 0xFFFFFFFF.toInt()

private fun CosmoColors.argb(field: Int): Int = live(field).toArgb()

/**
 * The cover's `glass` for a floating control: `surfaceHigh` at the glass opacity of the theme (52 % dark, 62 % light). The
 * engine has no separate `glass` colour yet (the v3 delta of `02-colour-delta.md` was not ported); `surfaceHigh` is the
 * nearest field (the solid fallback of the spec is `surfaceHigh` too), so this is the one place to change when it arrives.
 */
internal fun CosmoColors.glass(): Int = ColorMix.withAlpha(argb(CoverField.SURFACE_HIGH), GlassPolicy.tintAlpha(dark))

/** A CSS `linear-gradient(<angle>, from, to)` over the current box, with stops interpolated in OKLab. */
internal fun DrawScope.cssGradient(angleDeg: Float, from: Int, to: Int): Brush {
    val line = FloatArray(4)
    CssGradient.endpoints(angleDeg, size.width, size.height, line)
    val colors = ColorMix.stops(from, to, GRADIENT_STOPS).map { Color(it) }
    return Brush.linearGradient(colors, start = Offset(line[0], line[1]), end = Offset(line[2], line[3]))
}

// ---- B: in the page ------------------------------------------------------------------------------------------------------

private const val B_ANGLE = 160f
private const val HERO_ANGLE = 145f
private const val C_ANGLE = 135f

/** Primary: `accent` to `mix(accent, buttonGradientEnd, 0.5)` at 160 degrees; no glow. */
internal fun DrawScope.primaryFill(c: CosmoColors): Brush {
    val accent = c.argb(CoverField.ACCENT)
    return cssGradient(B_ANGLE, accent, ColorMix.mix(accent, c.argb(CoverField.BUTTON_GRADIENT_END), ControlMetrics.PRIMARY_END_MIX))
}

/** Secondary (tonal): `mix(accentContainer, accent, 0.09)` to `accentContainer` at 160 degrees. */
internal fun DrawScope.tonalFill(c: CosmoColors): Brush {
    val container = c.argb(CoverField.ACCENT_CONTAINER)
    return cssGradient(B_ANGLE, ColorMix.mix(container, c.argb(CoverField.ACCENT), ControlMetrics.TONAL_START_MIX), container)
}

/** The resting icon button: `mix(surfaceVariant, accent, 0.05)` to `surfaceVariant` at 160 degrees. */
internal fun DrawScope.iconFill(c: CosmoColors): Brush {
    val variant = c.argb(CoverField.SURFACE_VARIANT)
    return cssGradient(B_ANGLE, ColorMix.mix(variant, c.argb(CoverField.ACCENT), ControlMetrics.ICON_START_MIX), variant)
}

/** The hero play button, the one place the gradient is plainly visible: `buttonGradientStart` to `buttonGradientEnd` at 145 degrees. */
internal fun DrawScope.heroFill(c: CosmoColors): Brush =
    cssGradient(HERO_ANGLE, c.argb(CoverField.BUTTON_GRADIENT_START), c.argb(CoverField.BUTTON_GRADIENT_END))

/** `textPrimary` at [alpha]: the quiet button, the rest chip and segment, the switch track, a disabled button. */
internal fun CosmoColors.ink(alpha: Float): Color = live(CoverField.TEXT_PRIMARY).copy(alpha = alpha)

// ---- C: floating ---------------------------------------------------------------------------------------------------------

/** A glass control: the glass tint alone. */
internal fun CosmoColors.glassFill(): Brush = SolidColor(Color(glass()))

/** A C primary button: 46 % cover colour over the glass, `start` to `end` at 135 degrees. */
internal fun DrawScope.floatingPrimaryFill(c: CosmoColors): Brush = floatingGradient(c, ControlMetrics.C_PRIMARY_COLOUR_SHARE)

/** A C play button: 72 % cover colour over the glass. */
internal fun DrawScope.floatingPlayFill(c: CosmoColors): Brush = floatingGradient(c, ControlMetrics.C_PLAY_COLOUR_SHARE)

private fun DrawScope.floatingGradient(c: CosmoColors, colourShare: Float): Brush {
    val glass = c.glass()
    return cssGradient(
        C_ANGLE,
        ColorMix.mix(c.argb(CoverField.BUTTON_GRADIENT_START), glass, 1f - colourShare),
        ColorMix.mix(c.argb(CoverField.BUTTON_GRADIENT_END), glass, 1f - colourShare),
    )
}

/** The 1.5 dp gradient border of a C primary button: `mix(start, white, 0.25)` to `end` at 135 degrees. */
internal fun DrawScope.floatingBorder(c: CosmoColors): Brush =
    cssGradient(C_ANGLE, ColorMix.mix(c.argb(CoverField.BUTTON_GRADIENT_START), WHITE, ControlMetrics.C_PRIMARY_BORDER_WHITE_MIX), c.argb(CoverField.BUTTON_GRADIENT_END))

/** The ring of a C play button: `mix(start, white, 0.3)`. */
internal fun CosmoColors.floatingPlayRing(): Color =
    Color(ColorMix.mix(argb(CoverField.BUTTON_GRADIENT_START), WHITE, ControlMetrics.C_PLAY_RING_WHITE_MIX))

// ---- edges: drawn over the fill, inside the morphing outline ------------------------------------------------------------

/** A hairline along the top edge, inside the outline: CSS `inset 0 1px 0 <colour>`. It tapers away where the corners curve. */
internal fun DrawScope.topLine(outline: Path, color: Color, thicknessDp: Float = 1f) {
    val t = thicknessDp.dp.toPx()
    clipPath(outline) { drawLine(color, Offset(0f, t / 2f), Offset(size.width, t / 2f), strokeWidth = t) }
}

/** A ring that runs along the inside of the outline: CSS `inset 0 0 0 <width> <colour>`. */
internal fun DrawScope.innerRing(outline: Path, brush: Brush, widthDp: Float) {
    val w = widthDp.dp.toPx()
    clipPath(outline) { drawPath(outline, brush, style = Stroke(width = w * 2f, join = StrokeJoin.Round)) }
}

internal fun DrawScope.innerRing(outline: Path, color: Color, widthDp: Float) = innerRing(outline, SolidColor(color), widthDp)

/** The fine specular edge of a glass control: a 1 dp ring and a brighter hairline on top (light theme: a dark ring, a near-white top). */
internal fun DrawScope.glassEdge(outline: Path, dark: Boolean, ringAlpha: Float = ControlMetrics.glassRing(dark)) {
    innerRing(outline, (if (dark) Color.White else Color.Black).copy(alpha = ringAlpha), 1f)
    topLine(outline, Color.White.copy(alpha = ControlMetrics.glassTopHighlight(dark)))
}

/**
 * A soft drop shadow under the outline, CSS `0 <offset> <blur> <color>`. Compose has no portable blur for a path, so the
 * shadow is a few stacked translucent copies of the outline, each grown a little more and shifted down by [offsetDp]; together
 * they fall off the way a blur does. It is drawn under the fill, so only the part outside the outline shows.
 */
internal fun DrawScope.softShadow(outline: Path, color: Color, offsetDp: Float, blurDp: Float) {
    val layers = 3
    val dy = offsetDp.dp.toPx()
    val reach = blurDp.dp.toPx()
    for (i in layers downTo 1) {
        val layer = color.copy(alpha = color.alpha / layers)
        translate(0f, dy) {
            drawPath(outline, layer, style = Fill)
            drawPath(outline, layer, style = Stroke(width = 2f * reach * i / layers, join = StrokeJoin.Round))
        }
    }
}
