package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** A spring's stiffness and damping ratio (mass 1), as Compose's `spring(dampingRatio, stiffness)` takes them. */
data class SpringValues(val stiffness: Float, val dampingRatio: Float)

/** The motion table of the design formulas, section 6: the Material 3 Expressive springs, and the palette cross-fade. */
object Springs {
    val PRESS = SpringValues(800f, 0.6f)     // press, toggle, shape morph
    val MOVE = SpringValues(380f, 0.8f)      // mini player to Now Playing, sheets
    val SLOW = SpringValues(200f, 0.8f)      // track change: title, artwork
    val EFFECTS = SpringValues(1600f, 1.0f)  // colour and opacity of a control

    /** The whole-palette cross-fade is a tween of this many milliseconds. */
    const val PALETTE_MS = 700

    /** With reduced motion every spring becomes a fade of this many milliseconds. */
    const val REDUCED_MS = 150

    /** The first peak past the target, as a fraction of the distance travelled (0.095 is 9.5 %). */
    fun overshoot(s: SpringValues): Double =
        if (s.dampingRatio >= 1f) 0.0 else exp(-s.dampingRatio * PI / sqrt(1.0 - s.dampingRatio * s.dampingRatio))

    /** Milliseconds until the response envelope stays within [epsilon] of the target (mass 1). */
    fun settleMs(s: SpringValues, epsilon: Double = 0.001): Double {
        val omega = sqrt(s.stiffness.toDouble())
        val z = s.dampingRatio.toDouble()
        if (z < 1.0) return ln(1.0 / (epsilon * sqrt(1.0 - z * z))) / (z * omega) * 1000.0
        var x = 10.0   // critically damped: (1 + x) e^-x = epsilon, x = omega t
        repeat(30) { x -= ((1 + x) * exp(-x) - epsilon) / (-x * exp(-x)) }
        return x / omega * 1000.0
    }
}
