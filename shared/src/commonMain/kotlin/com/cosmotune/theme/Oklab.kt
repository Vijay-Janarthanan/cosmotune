package com.cosmotune.theme

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OKLab (Björn Ottosson, 2020): a colour space in which a straight line between two colours is a smooth,
 * even-looking blend, and the distance between two colours tracks how different they look. Used for blending
 * palettes ([CoverPalette.lerp]) and for telling whether two seeds are the same colour ([CoverPalette.settle]).
 * The colour derivation itself works in HCT.
 *
 * Pure functions on opaque ARGB integers; no state.
 */
internal object Oklab {

    /** The three coordinates of a colour: lightness (0 black to 1 white) and the two colour axes. */
    class Lab(val l: Double, val a: Double, val b: Double)

    fun fromArgb(argb: Int): Lab {
        val r = srgbToLinear((argb shr 16) and 0xFF)
        val g = srgbToLinear((argb shr 8) and 0xFF)
        val b = srgbToLinear(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Lab(
            l = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    /**
     * The opaque ARGB colour for [lab]. A colour the screen cannot show is brought in by lowering its chroma (the
     * two colour axes shrink together, lightness and hue stay), never by clipping one channel, which would turn the hue.
     */
    fun toArgb(lab: Lab): Int {
        var rgb = linearRgb(lab.l, lab.a, lab.b)
        if (!inGamut(rgb)) {
            var low = 0.0 // always in gamut: no chroma
            var high = 1.0 // out of gamut
            repeat(24) {
                val mid = (low + high) / 2.0
                if (inGamut(linearRgb(lab.l, lab.a * mid, lab.b * mid))) low = mid else high = mid
            }
            rgb = linearRgb(lab.l, lab.a * low, lab.b * low)
        }
        return (0xFF shl 24) or (linearToSrgb(rgb[0]) shl 16) or (linearToSrgb(rgb[1]) shl 8) or linearToSrgb(rgb[2])
    }

    /** The distance between two colours in OKLab, times 100: about 2 is the smallest difference an eye notices. */
    fun deltaE(first: Int, second: Int): Double {
        val x = fromArgb(first)
        val y = fromArgb(second)
        val dl = x.l - y.l
        val da = x.a - y.a
        val db = x.b - y.b
        return 100.0 * sqrt(dl * dl + da * da + db * db)
    }

    private const val GAMUT_SLACK = 1e-6

    private fun linearRgb(l: Double, a: Double, b: Double): DoubleArray {
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -GAMUT_SLACK && it <= 1.0 + GAMUT_SLACK }

    private fun srgbToLinear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Linear to an 8-bit sRGB channel. The value is within the gamut already, bar rounding noise. */
    private fun linearToSrgb(linear: Double): Int {
        val x = linear.coerceIn(0.0, 1.0)
        val c = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (c * 255.0).roundToInt().coerceIn(0, 255)
    }
}
