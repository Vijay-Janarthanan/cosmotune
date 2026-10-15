package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * CSS gradient angles for Compose. `linear-gradient(100deg, ...)` runs along `d = (sin a, -cos a)` (y down) through the centre
 * of the box, and its line is `|w sin a| + |h cos a|` long, so a gradient reaches both corners exactly. Compose's linear
 * gradient wants the two end points of that line.
 */
object CssGradient {
    /** Writes `x0, y0, x1, y1` of the gradient line for a box [width] x [height] at CSS angle [angleDeg] into [out] (size 4). */
    fun line(width: Float, height: Float, angleDeg: Float, out: FloatArray) {
        val a = angleDeg.toDouble() * PI / 180.0
        val dx = sin(a)
        val dy = -cos(a)
        val half = (abs(width * sin(a)) + abs(height * cos(a))) / 2.0
        val cx = width / 2.0
        val cy = height / 2.0
        out[0] = (cx - dx * half).toFloat()
        out[1] = (cy - dy * half).toFloat()
        out[2] = (cx + dx * half).toFloat()
        out[3] = (cy + dy * half).toFloat()
    }
}
