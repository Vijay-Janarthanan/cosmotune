package com.cosmotune.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.cosmotune.design.SizedLru
import kotlin.math.ceil

/*
 * Strokes along a superellipse, drawn from a mask made once (P4-21).
 *
 * The rims and hairlines of the new look (a cover's rim, a tile's edge, the specular edge of glass) are strokes along the outline,
 * of which the shape's clip shows the inside half. Along a rectangle or a rounded rectangle the GPU draws such a stroke
 * analytically. Along a path (every superellipse) it cannot: Skia rasterises the stroke into a coverage mask on the CPU, keyed to
 * where the path lands on the screen, so a scrolling page made those masks again for every cover and every tile on every frame.
 * On the emulator that was most of the new look's extra RenderThread time (8.5 ms a frame against the old look's 1.9 ms; without
 * the rims and edges 5 ms). Rasterised here once per shape, size, width and fade into an alpha mask, the stroke becomes one bitmap
 * draw, tinted with the colour of the moment, which the GPU keeps as a texture.
 */

/** The cache of [OutlineStroke] masks: one byte a pixel, least recently used out first. */
internal object OutlineMasks {
    /** 6 MB of masks: a 112 dp card's cover at 3x is 110 kB, so this holds the rims of several pages of cards. */
    const val BUDGET_BYTES: Long = 6L * 1024 * 1024

    private data class Key(
        val shape: Shape,
        val width: Int,
        val height: Int,
        val density: Float,
        val layoutDirection: LayoutDirection,
        val strokePx: Float,
        val fadePx: Float,
    )

    private val cache = SizedLru<Key, ImageBitmap>(BUDGET_BYTES) { it.width.toLong() * it.height }

    /**
     * A [strokePx] stroke along [shape]'s outline at [size] (pixels), in white, cut by the picture's bounds: solid, or when [fadePx] > 0
     * fading from white at the top edge to nothing [fadePx] below it. An alpha mask, made once and kept.
     */
    @Synchronized
    fun mask(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density, strokePx: Float, fadePx: Float): ImageBitmap {
        val width = ceil(size.width).toInt().coerceAtLeast(1)
        val height = ceil(size.height).toInt().coerceAtLeast(1)
        val key = Key(shape, width, height, density.density, layoutDirection, strokePx, fadePx)
        return cache.getOrPut(key) { rasterise(shape, Size(width.toFloat(), height.toFloat()), layoutDirection, density, strokePx, fadePx) }
    }

    /** How many bytes of masks are kept (tests). */
    internal val keptBytes: Long @Synchronized get() = cache.size

    @Synchronized
    internal fun clear() = cache.clear()

    private fun rasterise(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density, strokePx: Float, fadePx: Float): ImageBitmap {
        val image = ImageBitmap(size.width.toInt(), size.height.toInt(), ImageBitmapConfig.Alpha8)
        val outline = shape.createOutline(size, layoutDirection, density)
        // The whole stroke, not clipped here: the caller's clip cuts it to its inside half, as it cut the stroke drawn directly (a
        // clip here as well would dim the anti-aliased pixels along the edge a second time).
        CanvasDrawScope().draw(density, layoutDirection, Canvas(image), size) {
            val stroke = Stroke(strokePx)
            if (fadePx > 0f) drawOutline(outline, fadeBrush(Color.White, fadePx), style = stroke) else drawOutline(outline, Color.White, style = stroke)
        }
        return image
    }
}

/** [color] at the top edge, gone [fadePx] below it: a surface's top light. */
internal fun fadeBrush(color: Color, fadePx: Float): Brush =
    Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), startY = 0f, endY = fadePx)

/**
 * A stroke along one outline at one size, made in a draw cache by [outlineStroke]: [draw] lays the inside half of it in a colour
 * that may be read live (a palette fade passes a new one each frame). A path's stroke comes from [OutlineMasks]; a rectangle's or
 * a rounded rectangle's is stroked directly, which is as cheap.
 */
internal class OutlineStroke internal constructor(
    private val outline: Outline,
    private val mask: ImageBitmap?,
    private val stroke: Stroke,
    private val fadePx: Float,
) {
    fun draw(scope: DrawScope, color: Color) {
        when {
            mask != null -> scope.drawImage(mask, Offset.Zero, colorFilter = ColorFilter.tint(color))
            fadePx > 0f -> scope.drawOutline(outline, fadeBrush(color, fadePx), style = stroke)
            else -> scope.drawOutline(outline, color, style = stroke)
        }
    }

    /** Whether this stroke is drawn from a mask (tests). */
    internal val masked: Boolean get() = mask != null
}

/**
 * The stroke of [strokePx] along [outline] ([shape]'s outline at [size]), solid or fading out [fadePx] below the top. The caller has
 * clipped to [shape], so the inside half shows, as it did when the outline was stroked directly.
 */
internal fun Density.outlineStroke(
    shape: Shape,
    outline: Outline,
    size: Size,
    layoutDirection: LayoutDirection,
    strokePx: Float,
    fadePx: Float = 0f,
): OutlineStroke {
    val mask = if (outline is Outline.Generic) OutlineMasks.mask(shape, size, layoutDirection, this, strokePx, fadePx) else null
    return OutlineStroke(outline, mask, Stroke(strokePx), fadePx)
}
