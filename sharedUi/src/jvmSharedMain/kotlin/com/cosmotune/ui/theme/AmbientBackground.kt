package com.cosmotune.ui.theme

import androidx.compose.foundation.layout.Box
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
import com.cosmotune.theme.CoverField

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
        val linear = Brush.verticalGradient(
            0f to colors.live(CoverField.BACKGROUND_TOP), 0.46f to colors.live(CoverField.BACKGROUND_MID),
            0.82f to colors.live(CoverField.BACKGROUND_BOTTOM), endY = size.height,
        )
        if (!artwork) {
            // The cover's accent (its strongest colour), moved toward black or white only as far as the page's text needs.
            val tint = readableBehind(colors.live(CoverField.ACCENT), colors.live(CoverField.TEXT_PRIMARY))
            val cover = Brush.verticalGradient(
                0f to tint.copy(alpha = wash), 0.35f to tint.copy(alpha = 0.55f * wash), 0.8f to tint.copy(alpha = 0f), endY = size.height,
            )
            return@drawWithCache onDrawBehind { drawRect(linear); if (wash > 0f) drawRect(cover) }
        }
        val bottom = colors.live(CoverField.BACKGROUND_BOTTOM)
        val fade = Brush.verticalGradient(
            0f to readableBehind(colors.live(CoverField.ACCENT_CONTAINER), colors.live(CoverField.TEXT_PRIMARY)),
            0.65f to bottom,
            endY = size.height,
        )
        onDrawBehind { drawRect(fade) }
    }
}

/** WCAG relative luminance of an opaque colour. */
internal fun relativeLuminance(c: Color): Float {
    fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}

/**
 * [ground] moved toward black (for light [ink]) or white (for dark ink) just enough that [ink] keeps [ratio]:1 on it, hue kept as
 * far as the move allows; unchanged when it already does. The luminance cap of the artwork backdrop.
 */
fun readableBehind(ground: Color, ink: Color, ratio: Float = 4.5f): Color {
    val inkL = relativeLuminance(ink)
    fun contrast(c: Color): Float { val l = relativeLuminance(c); return (maxOf(l, inkL) + 0.05f) / (minOf(l, inkL) + 0.05f) }
    if (contrast(ground) >= ratio) return ground
    val toward = if (inkL > 0.18f) Color.Black else Color.White
    var lo = 0f
    var hi = 1f
    repeat(20) {
        val mid = (lo + hi) / 2f
        if (contrast(androidx.compose.ui.graphics.lerp(ground, toward, mid).copy(alpha = 1f)) >= ratio) hi = mid else lo = mid
    }
    return androidx.compose.ui.graphics.lerp(ground, toward, hi).copy(alpha = ground.alpha)
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) = Box(modifier.cosmoAmbientGround())
