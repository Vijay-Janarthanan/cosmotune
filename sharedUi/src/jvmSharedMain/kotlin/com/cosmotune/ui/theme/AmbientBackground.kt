package com.cosmotune.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.cosmotune.theme.CoverField
import com.cosmotune.theme.CoverWash
import com.cosmotune.ui.orbit.NEUTRAL_CARD_SEED
import com.cosmotune.ui.orbit.vivid

/** A radial glow squashed to an ellipse ([rx] x [ry]) at ([cx], [cy]); [stops] are (fraction of rx, colour). */
fun DrawScope.drawEllipseGlow(stops: Array<Pair<Float, Color>>, cx: Float, cy: Float, rx: Float, ry: Float) {
    val k = ry / rx
    withTransform({ scale(1f, k, pivot = Offset(cx, cy)) }) {
        val top = cy - cy / k
        val bottom = cy + (size.height - cy) / k
        drawRect(Brush.radialGradient(*stops, center = Offset(cx, cy), radius = rx), topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
    }
}

/**
 * The ground of every screen (docs/design/REVAMP_DIRECTION.md: neutral chrome, colour from content): the near-neutral vertical
 * gradient `backgroundTop` (0), `backgroundMid` (0.46), `backgroundBottom` (0.82), with no glow and no grain, so lists and pages
 * stay calm and the artwork and thumbnails bring the colour.
 *
 * With [artwork] (the player) it is the cover's colour at the top fading to the ground by 65 % of the height (DESIGN_SYSTEM.md 2.3). The colours are
 * the ones on screen this frame: they are read inside the cache block, so a fade rebuilds the cache (a brush or two a frame) and
 * recomposes nothing.
 */
@Composable
fun Modifier.cosmoAmbientGround(artwork: Boolean = false): Modifier {
    val colors = LocalCosmoColors.current
    // While the app follows a cover (the palette is a cover's, not the neutral One palette), every page wears that cover's colour
    // as a wash from the top, fading into the ground; it comes and goes with the palette instead of snapping.
    val wash by animateFloatAsState(if (colors.palette.isFallback) 0f else 1f, tween(600), label = "wash")
    return drawWithCache {
        // Live colours are read here, so the cache is rebuilt only while a fade runs.
        val brushes = groundBrushes(colors, wash, artwork, size.height)
        onDrawBehind { brushes.forEach { drawRect(it) } }
    }
}

/**
 * The ground's brushes for a page [height] tall: the linear gradient, then the cover's wash while there is one ([wash] its share);
 * the player's [artwork] fade alone with [artwork].
 *
 * Both are the one colour of [coverWash]: the song card's fill made into a ground, so the card and the page are one family.
 */
private fun groundBrushes(colors: CosmoColors, wash: Float, artwork: Boolean, height: Float): List<Brush> {
    val tint = coverWash(colors, wash)
    if (artwork) {
        val bottom = colors.live(CoverField.BACKGROUND_BOTTOM)
        return listOf(Brush.verticalGradient(0f to tint, 0.65f to bottom, endY = height))
    }
    val linear = Brush.verticalGradient(
        0f to colors.live(CoverField.BACKGROUND_TOP), 0.46f to colors.live(CoverField.BACKGROUND_MID),
        0.82f to colors.live(CoverField.BACKGROUND_BOTTOM), endY = height,
    )
    if (wash <= 0f) return listOf(linear)
    val cover = Brush.verticalGradient(
        0f to tint.copy(alpha = wash), 0.35f to tint.copy(alpha = 0.55f * wash), 0.8f to tint.copy(alpha = 0f), endY = height,
    )
    return listOf(linear, cover)
}

/**
 * The cover's wash this frame: the song card's fill ([coverCardTint] of the live seed: what `coverFill` paints the card with) made
 * into a ground by [CoverWash.ground], the card's OKLCH hue and chroma at a lightness all the page's text reads on (4.5:1), just under
 * the card's in the dark theme and a pale tint of it in the light one. With no cover followed ([wash] 0: the brand or One palette,
 * a grey cover) it is the neutral charcoal card's ground; between the two it blends in OKLab as the palette fades.
 */
internal fun coverWash(colors: CosmoColors, wash: Float): Color {
    val primary = colors.live(CoverField.TEXT_PRIMARY).toArgb()
    val secondary = colors.live(CoverField.TEXT_SECONDARY).toArgb()
    val tertiary = colors.live(CoverField.TEXT_TERTIARY).toArgb()
    fun groundOf(seed: Int) = Color(CoverWash.ground(vivid(seed).fill.toArgb(), colors.dark, primary, secondary, tertiary))
    val neutral = groundOf(NEUTRAL_CARD_SEED)
    if (wash <= 0f) return neutral
    val cover = groundOf(colors.live(CoverField.SEED).toArgb())
    return if (wash >= 1f) cover else androidx.compose.ui.graphics.lerp(neutral, cover, wash)
}

/**
 * The page's own ground behind a bar at the top of the window (the phone's top bar, a status-bar band): the gradient and the cover's
 * wash exactly as [cosmoAmbientGround] paints them on the whole window, so at rest the bar cannot be told from the page, and content
 * scrolling under it goes under the ground instead of under the bar's words. [shown] (0 to 1, read only while drawing) fades it in
 * once something is under the bar; below its bounds the ground runs on for [fade], fading out, so the edge is soft.
 */
@Composable
fun Modifier.cosmoTopGround(shown: () -> Float, fade: Dp = 14.dp): Modifier {
    val colors = LocalCosmoColors.current
    val wash by animateFloatAsState(if (colors.palette.isFallback) 0f else 1f, tween(600), label = "topWash")
    val pageHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    return drawWithCache {
        val brushes = groundBrushes(colors, wash, artwork = false, height = if (pageHeight > 0f) pageHeight else size.height * 8f)
        val fadePx = fade.toPx()
        val mask = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height, endY = size.height + fadePx)
        val band = Size(size.width, size.height + fadePx)
        onDrawBehind {
            val alpha = shown().coerceIn(0f, 1f)
            if (alpha <= 0f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, band), Paint().apply { this.alpha = alpha })
                brushes.forEach { drawRect(it, size = band) }
                // Below the bar the ground fades out: the mask keeps the bar's own height whole.
                drawRect(mask, topLeft = Offset(0f, size.height), size = Size(size.width, fadePx), blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }
}

/**
 * [ground] moved in OKLCH lightness, away from [ink], just far enough that [ink] keeps [ratio]:1 on it ([CoverWash.readable]): the
 * hue and the chroma are kept (only where sRGB has no such colour is the chroma lowered), so a deep colour never goes muddy the way
 * a mix toward black does. Unchanged when it already reads. The luminance cap of the artwork backdrop.
 */
fun readableBehind(ground: Color, ink: Color, ratio: Float = 4.5f): Color {
    val argb = ground.toArgb()
    val moved = CoverWash.readable(argb, ink.copy(alpha = 1f).toArgb(), ratio.toDouble())
    return if (moved == argb) ground else Color(moved)
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) = Box(modifier.cosmoAmbientGround())

/**
 * The ground under a mode whose pages paint none of their own (Watch, Read and Manga stand on whatever their host lays down): the
 * same [cosmoAmbientGround] as Music's root and the desktop's frame, so the ground and the pages' ink come from one palette. The
 * phone's window is black in both themes, and without this the light theme's dark ink stood on it.
 */
@Composable
fun ModeGround(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().cosmoAmbientGround().testTag("mode:ground")) { content() }
}
