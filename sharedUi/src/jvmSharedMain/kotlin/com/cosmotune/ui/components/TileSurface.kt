package com.cosmotune.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TileDepth
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoColors

/*
 * What lifts a B tile off the page ([TileDepth]; `screens-v3.css` `.tile2`, `.mix2`, `.mtile`, `.pin2`: `box-shadow: var(--hl), var(--e1)`):
 * the lift under it and the highlight over its fill. A tile's fill sits within 1.05 to 1.25:1 of the ground, so these are what make it
 * read as a raised block, in the light theme most of all. Every colour is read while drawing (the layer block, the draw lambdas), so a
 * palette fade moves them and recomposes nothing.
 */

/** The black of the ambient shadow under a tile (`0 1px 2px rgb(0 0 0 / .28)`). */
private val TILE_AMBIENT = Color.Black.copy(alpha = TileDepth.AMBIENT_ALPHA)

/**
 * The lift under a tile (`--e1`): an elevation of [elevation] whose key shadow is the palette's `shadow` at full strength (the platform
 * scales a key shadow down to about a fifth, as it does under the cover), over a black ambient one. See [liftShadow].
 */
internal fun Modifier.tileLift(shape: Shape, colors: CosmoColors, elevation: Dp = TileDepth.ELEVATION_DP.dp): Modifier =
    liftShadow(shape, elevation, spot = { colors.live(CoverField.SHADOW).copy(alpha = 1f) }, ambient = { TILE_AMBIENT })

/** The same lift for a [MorphBox], which applies it in its own layer, so the shadow shrinks with the press. */
internal fun tileLiftOf(shape: Shape, colors: CosmoColors, elevation: Dp = TileDepth.ELEVATION_DP.dp): Lift =
    Lift(shape, elevation, spot = { colors.live(CoverField.SHADOW).copy(alpha = 1f) }, ambient = { TILE_AMBIENT })

/**
 * The highlight over a tile's fill (`--hl`), drawn inside [shape]'s outline (the caller has clipped to the shape): a 1 dp top-lit rim in
 * white (10 % dark, 80 % light) fading down the sides, and a 0.5 dp hairline in the ink (`textPrimary`, the colour the screens draw the
 * palette's `outline` with) at 7 % dark, 6 % light. Made in a draw cache: the strokes are built once per size and per palette (the
 * theme picks the rim, and reading the palette here rebuilds the cache when it changes), a superellipse's from masks kept across
 * tiles of one size ([outlineStroke]); the ink is read live by the drawing that comes back.
 */
internal fun CacheDrawScope.tileEdge(shape: Shape, colors: CosmoColors): DrawScope.() -> Unit {
    keepRedrawnWith(colors.palette)
    val outline = shape.createOutline(size, layoutDirection, this)
    val rimColor = Color.White.copy(alpha = TileDepth.rimAlpha(colors.dark))
    // the clip shows the inner half of each stroke
    val rim = outlineStroke(shape, outline, size, layoutDirection, (TileDepth.RIM_DP * 2f).dp.toPx(), fadePx = topLightFade(size.height))
    val hair = outlineStroke(shape, outline, size, layoutDirection, (TileDepth.HAIRLINE_DP * 2f).dp.toPx())
    val hairAlpha = TileDepth.hairlineAlpha(colors.dark)
    return {
        rim.draw(this, rimColor)
        hair.draw(this, colors.live(CoverField.TEXT_PRIMARY).copy(alpha = hairAlpha))
    }
}

/** The same highlight along a morphing outline (a [MorphBox] edge, which hands over the outline as a [Path]): clipped to it here. */
internal fun DrawScope.tileEdge(outline: Path, colors: CosmoColors) {
    val ink = colors.live(CoverField.TEXT_PRIMARY).copy(alpha = TileDepth.hairlineAlpha(colors.dark))
    clipPath(outline) {
        drawPath(outline, topLightBrush(Color.White.copy(alpha = TileDepth.rimAlpha(colors.dark)), size.height), style = Stroke((TileDepth.RIM_DP * 2f).dp.toPx()))
        drawPath(outline, ink, style = Stroke((TileDepth.HAIRLINE_DP * 2f).dp.toPx()))
    }
}
