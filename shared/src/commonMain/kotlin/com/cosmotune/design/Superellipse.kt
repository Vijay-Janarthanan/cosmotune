package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rounded rectangles with superellipse corners, |x|^n + |y|^n = 1 (02 section 3). n = 2 is a circular corner (a circle
 * when the radius is half the side); n = 4 is the design's squircle. Writes points and never allocates.
 */
object Superellipse {
    const val SEGMENTS = 16
    const val BUFFER_SIZE = 4 * (SEGMENTS + 1) * 2
    private const val HALF_PI = (PI / 2).toFloat()

    /**
     * Writes the outline into [out] as (x, y) pairs, clockwise from the top edge: top-right, bottom-right, bottom-left,
     * top-left corner, each of `SEGMENTS + 1` points. [out] needs [BUFFER_SIZE] floats. Returns the number of points.
     */
    fun outline(width: Float, height: Float, radius: Float, exponent: Float, out: FloatArray): Int {
        val r = radius.coerceIn(0f, min(width, height) / 2f)
        val p = 2f / exponent.coerceAtLeast(1f)
        var n = 0
        for (corner in 0 until 4) {
            val right = corner == 0 || corner == 1
            val bottom = corner == 1 || corner == 2
            val cx = if (right) width - r else r
            val cy = if (bottom) height - r else r
            for (i in 0..SEGMENTS) {
                val q = i.toFloat() / SEGMENTS * HALF_PI
                val theta = if (corner % 2 == 0) HALF_PI - q else q
                out[2 * n] = cx + (if (right) 1f else -1f) * r * cos(theta).coerceAtLeast(0f).pow(p)
                out[2 * n + 1] = cy + (if (bottom) 1f else -1f) * r * sin(theta).coerceAtLeast(0f).pow(p)
                n++
            }
        }
        return n
    }

    /**
     * The radius of the circular corner that stands in for a superellipse corner of [radius] and [exponent] under its elevation
     * shadow: the circle through the superellipse's own 45 degree point, `r (1 - 2^(-1/n)) / (1 - 2^(-1/2))` (0.543 r for the
     * design's n = 4, r itself for a circle). A soft shadow cannot show the fraction of a pixel between the two curves, and the
     * platform draws a rounded rectangle's shadow analytically where a path's is tessellated on the CPU again on every frame
     * that it moves (P4-21).
     */
    fun shadowRadius(radius: Float, exponent: Float): Float {
        val n = exponent.coerceAtLeast(1f)
        return radius * (1f - 2f.pow(-1f / n)) / (1f - 2f.pow(-0.5f))
    }
}
