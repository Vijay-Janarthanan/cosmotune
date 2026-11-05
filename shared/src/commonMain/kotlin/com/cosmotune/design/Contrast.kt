package com.cosmotune.design

import com.cosmotune.theme.Oklab
import kotlin.math.max
import kotlin.math.min

/** WCAG 2 contrast, for checking what the palette engine hands out and for choosing between two inks. */
object Contrast {
    /** Relative luminance of an opaque sRGB colour (alpha is ignored). */
    private fun luminance(argb: Int): Double =
        0.2126 * Oklab.srgbToLinear((argb shr 16) and 0xFF) +
            0.7152 * Oklab.srgbToLinear((argb shr 8) and 0xFF) +
            0.0722 * Oklab.srgbToLinear(argb and 0xFF)

    /** `(L1 + 0.05) / (L2 + 0.05)` with L1 the lighter colour, from 1.0 (equal) to 21.0 (black and white). */
    fun ratio(argbA: Int, argbB: Int): Double {
        val a = luminance(argbA)
        val b = luminance(argbB)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
