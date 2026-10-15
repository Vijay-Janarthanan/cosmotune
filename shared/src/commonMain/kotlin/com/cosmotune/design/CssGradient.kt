package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The line of a CSS `linear-gradient(<angle>, ...)` in a box: it points along (sin a, -cos a) (y down), passes through the
 * centre, and is as long as the box needs for the first colour to sit exactly on one corner and the last on the opposite one
 * (`|w sin a| + |h cos a|`). Compose's `Brush.linearGradient(start, end)` takes the same two points.
 */
object CssGradient {
    /** Writes `startX, startY, endX, endY` of the gradient line in a [width] x [height] box into [out]. */
    fun endpoints(angleDeg: Float, width: Float, height: Float, out: FloatArray) {
        val a = angleDeg * PI.toFloat() / 180f
        val dx = sin(a)
        val dy = -cos(a)
        val half = (abs(width * dx) + abs(height * dy)) / 2f
        val cx = width / 2f
        val cy = height / 2f
        out[0] = cx - dx * half
        out[1] = cy - dy * half
        out[2] = cx + dx * half
        out[3] = cy + dy * half
    }
}
