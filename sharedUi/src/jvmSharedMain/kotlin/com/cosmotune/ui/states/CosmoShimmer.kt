package com.cosmotune.ui.states

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import com.cosmotune.design.CssGradient
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion

/*
 * The skeleton of the new look (`.skel` and `@keyframes shimmer`, `components-v3bc.css:164-165`; `--shimmer-ms: 1400ms`):
 *
 *     background: linear-gradient(100deg, skel 30%, skel-shine 50%, skel 70%);
 *     background-size: 300% 100%;
 *     animation: shimmer 1400ms linear infinite;      from { background-position: 100% 0 } to { background-position: -50% 0 }
 *
 * The image is three times as wide as the block; its position slides from 100% to -50% of the free width (the block's width minus the
 * image's), which is exactly one image width of travel, and the image repeats, so the last frame is the first and the loop has no seam.
 * Both ends of the gradient (below 30 % and above 70 %) are plain `skelBase`, so a block shows the highlight for most of the cycle and
 * then rests as flat colour for the rest.
 */

/** `--shimmer-ms`: one sweep. */
private const val SHIMMER_MS = 1400

/** The angle of the gradient, CSS degrees. */
private const val GRADIENT_ANGLE_DEG = 100f

/** Where the fill turns into the highlight, the highlight's peak and where it is gone again (fractions of the gradient line). */
private const val STOP_BEFORE = 0.30f
private const val STOP_SHINE = 0.50f
private const val STOP_AFTER = 0.70f

/** `background-size: 300% 100%`: the image is this many block widths wide. */
private const val IMAGE_WIDTHS = 3f

/** `background-position` along the animation, as fractions of (block width - image width). */
private const val POSITION_FROM = 1.0f
private const val POSITION_TO = -0.5f

/**
 * One placeholder block in the new look: it takes the shape the caller gives it and the cover's own `skelBase`, with a highlight of
 * `skelShine` sweeping across it every 1.4 s. Under reduced motion it is a still `skelBase` fill and no animation runs at all.
 *
 * The colours are read while drawing (the cache of `drawWithCache`), so a palette fade repaints it and never recomposes it. The sweep is
 * read while drawing too, in the draw lambda, which only moves the picture: a screenful of these (a feed skeleton has dozens) costs no
 * recomposition and no allocation per frame.
 */
@Composable
fun CosmoShimmer(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(CosmoRadii.xs)) {
    if (LocalReducedMotion.current) StillSkeleton(modifier, shape) else SweepingSkeleton(modifier, shape)
}

/** Reduced motion: the base fill, still. */
@Composable
private fun StillSkeleton(modifier: Modifier, shape: Shape) {
    val colors = LocalCosmoColors.current
    Box(modifier.clip(shape).drawBehind { drawRect(colors.live(CoverField.SKEL_BASE)) })
}

@Composable
private fun SweepingSkeleton(modifier: Modifier, shape: Shape) {
    val colors = LocalCosmoColors.current
    val sweep = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                // the picture: the 300 % wide image, made once per size and per palette frame; the sweep only moves it
                val imageWidth = size.width * IMAGE_WIDTHS
                val line = FloatArray(4)
                CssGradient.endpoints(GRADIENT_ANGLE_DEG, imageWidth, size.height, line)
                val base = colors.live(CoverField.SKEL_BASE)
                val shine = colors.live(CoverField.SKEL_SHINE)
                val brush = Brush.linearGradient(
                    STOP_BEFORE to base, STOP_SHINE to shine, STOP_AFTER to base,
                    start = Offset(line[0], line[1]), end = Offset(line[2], line[3]),
                )
                val freeWidth = size.width - imageWidth
                onDrawBehind {
                    // `background-position` at this moment of the sweep, then the picture slid to it (the rectangle is the block, in the
                    // slid coordinates)
                    val x = freeWidth * (POSITION_FROM + (POSITION_TO - POSITION_FROM) * sweep.value)
                    translate(left = x) { drawRect(brush, topLeft = Offset(-x, 0f), size = size) }
                }
            },
    )
}
