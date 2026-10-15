package com.cosmotune.design

import com.cosmotune.theme.OklabMath
import kotlin.math.roundToInt

/**
 * `mix(a, b, t)` of the component spec (`v3/01-components-spec-bc.md`, "Conventions for Compose"): OKLab interpolation with
 * premultiplied alpha, the way the palette cross-fade blends, as plain ARGB `Int`s. [t] 0 is [a], 1 is [b].
 *
 * Compose interpolates the stops of a gradient in sRGB; a control builds its gradient from a few stops made here instead
 * ([stops]), so a two-colour gradient of the design reads the same as the CSS `linear-gradient(in oklab ...)` it comes from.
 */
object ColorMix {
    fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val la = DoubleArray(4)
        val lb = DoubleArray(4)
        OklabMath.fromArgb(a, la, 0)
        OklabMath.fromArgb(b, lb, 0)
        val x = t.toDouble()
        val alpha = la[3] + (lb[3] - la[3]) * x
        if (alpha <= 0.0) return 0
        // premultiplied: a colour that is not there (alpha 0) carries no weight
        fun channel(i: Int) = (la[i] * la[3] * (1 - x) + lb[i] * lb[3] * x) / alpha
        return OklabMath.toArgb(channel(0), channel(1), channel(2), alpha)
    }

    /** [argb] with its opacity replaced by [alpha] (0..1). */
    fun withAlpha(argb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (argb and 0xFFFFFF)

    /** [count] colours from [a] to [b], evenly spaced in OKLab: the stops of a gradient. */
    fun stops(a: Int, b: Int, count: Int): IntArray {
        if (count <= 1) return intArrayOf(a)
        return IntArray(count) { i -> mix(a, b, i.toFloat() / (count - 1)) }
    }
}
