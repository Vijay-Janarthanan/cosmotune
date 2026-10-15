package com.cosmotune.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
 * The ambient ground of every screen (`screens-v2.css:7-12`): two elliptical radials of `ambient` over a vertical gradient
 * `backgroundTop` (0), `backgroundMid` (0.46), `backgroundBottom` (0.82). The colours are the ones on screen this frame:
 * they are read inside the cache block, so a fade rebuilds the cache (a brush or two a frame) and recomposes nothing.
 */
@Composable
fun Modifier.cosmoAmbientGround(): Modifier {
    val colors = LocalCosmoColors.current
    val grain = rememberGrainBrush()
    return drawWithCache {
        // Live colours are read here, so the cache is rebuilt only while a fade runs.
        val ambient = colors.live(CoverField.AMBIENT)
        val linear = Brush.verticalGradient(
            0f to colors.live(CoverField.BACKGROUND_TOP), 0.46f to colors.live(CoverField.BACKGROUND_MID),
            0.82f to colors.live(CoverField.BACKGROUND_BOTTOM), endY = size.height,
        )
        val glowA = arrayOf(0f to ambient, 0.72f to ambient.copy(alpha = 0f))
        val glowB = arrayOf(0f to ambient.copy(alpha = ambient.alpha * 0.7f), 0.70f to ambient.copy(alpha = 0f))
        onDrawBehind {
            drawRect(linear)
            drawEllipseGlow(glowA, 0.18f * size.width, -0.04f * size.height, 0.85f * size.width, 0.48f * size.height)
            drawEllipseGlow(glowB, 1.05f * size.width, 0.38f * size.height, 0.60f * size.width, 0.40f * size.height)
            drawGrain(grain, colors.dark)
        }
    }
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) = Box(modifier.cosmoAmbientGround())
