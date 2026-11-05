package com.cosmotune.theme

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * OKLab (Bjorn Ottosson, 2020) for the part of the app that runs every frame: the same matrices as [Oklab], but writing
 * into caller-owned arrays, so blending two palettes allocates nothing. [Oklab] stays the one the palette is derived
 * with (it returns arrays, which is fine once per cover); the golden tests pin that one, and `PaletteBlendTest` checks
 * that this one blends like [CoverPalette.lerp].
 */
internal object OklabMath {

    /** Writes the OKLab L, a, b and the alpha (0..1) of [argb] to `out[offset .. offset + 3]`. */
    fun fromArgb(argb: Int, out: DoubleArray, offset: Int) {
        val r = toLinear((argb shr 16) and 0xFF)
        val g = toLinear((argb shr 8) and 0xFF)
        val b = toLinear(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        out[offset] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[offset + 1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[offset + 2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        out[offset + 3] = (argb ushr 24) / 255.0
    }

    /** The ARGB colour of an OKLab triple and an alpha (0..1), each channel clipped to the sRGB cube and rounded. */
    fun toArgb(l: Double, a: Double, b: Double, alpha: Double): Int {
        val lp = l + 0.3963377774 * a + 0.2158037573 * b
        val mp = l - 0.1055613458 * a - 0.0638541728 * b
        val sp = l - 0.0894841775 * a - 1.2914855480 * b
        val lc = lp * lp * lp
        val mc = mp * mp * mp
        val sc = sp * sp * sp
        val r = fromLinear(4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc)
        val g = fromLinear(-1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc)
        val bl = fromLinear(-0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc)
        val a8 = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (a8 shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun toLinear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun fromLinear(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val c = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (c * 255.0).roundToInt().coerceIn(0, 255)
    }
}
