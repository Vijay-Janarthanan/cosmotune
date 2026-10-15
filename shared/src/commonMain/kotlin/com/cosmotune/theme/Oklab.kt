package com.cosmotune.theme

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour as OKLCH: lightness 0 to 1, chroma, hue in degrees. */
internal class Lch(val l: Double, val c: Double, val h: Double)

/**
 * OKLab (Bjorn Ottosson, 2020) and OKLCH: the colour space the cover palette is derived in, and the space in which
 * palettes are blended and compared. A straight port of the colour maths in the designer's reference implementation,
 * `dynamic-colour.js`, with the same matrices, the same sRGB transfer function and the same rounding, so that the
 * numbers agree (the golden vectors in the tests check it).
 *
 * Pure functions; no state. Triples are `DoubleArray(3)`.
 */
internal object Oklab {

    private const val RAD = PI / 180.0

    /** sRGB channel 0..255 to linear light. */
    fun srgbToLinear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Linear light to sRGB 0..1 (not rounded). */
    fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055

    fun linearToOklab(r: Double, g: Double, b: Double): DoubleArray {
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
        )
    }

    fun oklabToLinear(l: Double, a: Double, b: Double): DoubleArray {
        val lc = cube(l + 0.3963377774 * a + 0.2158037573 * b)
        val mc = cube(l - 0.1055613458 * a - 0.0638541728 * b)
        val sc = cube(l - 0.0894841775 * a - 1.291485548 * b)
        return doubleArrayOf(
            4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
            -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
            -0.0041960863 * lc - 0.7034186147 * mc + 1.707614701 * sc,
        )
    }

    private fun cube(x: Double) = x * x * x

    fun lchToLab(l: Double, c: Double, h: Double): DoubleArray = doubleArrayOf(l, c * cos(h * RAD), c * sin(h * RAD))

    fun labToLch(l: Double, a: Double, b: Double): Lch = Lch(l, hypot(a, b), ((atan2(b, a) / RAD) + 360.0) % 360.0)

    fun rgbToOklab(r: Int, g: Int, b: Int): DoubleArray = linearToOklab(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))

    /** The OKLab triple of an opaque ARGB colour (its alpha is ignored). */
    fun fromArgb(argb: Int): DoubleArray = rgbToOklab((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    fun lchFromArgb(argb: Int): Lch = fromArgb(argb).let { labToLch(it[0], it[1], it[2]) }

    /** Euclidean distance in OKLab: deltaE_OK, where 0.02 is about one just-noticeable difference. */
    fun distance(a: DoubleArray, b: DoubleArray): Double = hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2])

    fun distance(argb1: Int, argb2: Int): Double = distance(fromArgb(argb1), fromArgb(argb2))

    /** An OKLab triple to an ARGB colour by clipping each channel, as the reference does when it blends two palettes. */
    fun toArgb(lab: DoubleArray, alpha8: Int = 0xFF): Int {
        val lin = oklabToLinear(lab[0], lab[1], lab[2])
        fun channel(v: Double) = (linearToSrgb(v.coerceIn(0.0, 1.0)) * 255.0).roundToInt().coerceIn(0, 255)
        return (alpha8 shl 24) or (channel(lin[0]) shl 16) or (channel(lin[1]) shl 8) or channel(lin[2])
    }
}

/**
 * A colour as the derivation sees it: what was asked for ([req], OKLCH, before gamut mapping), the in-gamut colour
 * that came out (8-bit [rgb], its real OKLab and OKLCH, relative luminance [y]) and an alpha. Port of `mk()`.
 */
internal class Tok(
    val l: Double,
    val c: Double,
    val h: Double,
    val req: DoubleArray,
    val rgb: IntArray,
    val lab: DoubleArray,
    val y: Double,
    val a: Double,
) {
    val argb: Int get() = (((a * 255.0).roundToInt().coerceIn(0, 255)) shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
}

/**
 * The designer's colour construction: CSS Color 4 gamut mapping (keep L and h, binary-search the chroma until the
 * clipped colour is within [jnd] of the unclipped one, then clip), WCAG contrast, and the "inside the gamut" margin.
 * Port of `gamutMapLin`, `mk`, `over`, `contrast`, `maxChroma` and `inside` in `dynamic-colour.js`.
 */
internal class ColourMath(private val jnd: Double, private val epsilon: Double, private val gamutMargin: Double) {

    private fun inGamut(lin: DoubleArray) = lin.all { it >= -0.0001 && it <= 1.0001 }

    private fun clip(lin: DoubleArray) = DoubleArray(3) { min(1.0, max(0.0, lin[it])) }

    private fun lchLin(l: Double, c: Double, h: Double): DoubleArray = Oklab.lchToLab(l, c, h).let { Oklab.oklabToLinear(it[0], it[1], it[2]) }

    private fun linLab(lin: DoubleArray) = Oklab.linearToOklab(lin[0], lin[1], lin[2])

    private fun gamutMapLin(l: Double, c: Double, h: Double): DoubleArray {
        if (l >= 1) return doubleArrayOf(1.0, 1.0, 1.0)
        if (l <= 0) return doubleArrayOf(0.0, 0.0, 0.0)
        var lin = lchLin(l, c, h)
        if (inGamut(lin)) return clip(lin)
        var lo = 0.0
        var hi = c
        var loInGamut = true
        var clipped = clip(lin)
        if (Oklab.distance(linLab(clipped), Oklab.lchToLab(l, c, h)) < jnd) return clipped
        while (hi - lo > epsilon) {
            val mid = (lo + hi) / 2
            lin = lchLin(l, mid, h)
            if (loInGamut && inGamut(lin)) {
                lo = mid
                continue
            }
            clipped = clip(lin)
            val e = Oklab.distance(linLab(clipped), Oklab.lchToLab(l, mid, h))
            if (e < jnd) {
                if (jnd - e < epsilon) break
                loInGamut = false
                lo = mid
            } else {
                hi = mid
            }
        }
        return clip(lchLin(l, lo, h))
    }

    /** A colour token: requested OKLCH in, in-gamut sRGB out. */
    fun mk(l: Double, c: Double, h: Double, alpha: Double = 1.0): Tok {
        val lin = gamutMapLin(l, max(0.0, c), ((h % 360.0) + 360.0) % 360.0)
        val rgb = IntArray(3) { (Oklab.linearToSrgb(lin[it]) * 255.0).roundToInt() }
        val lab = Oklab.rgbToOklab(rgb[0], rgb[1], rgb[2])
        val lch = Oklab.labToLch(lab[0], lab[1], lab[2])
        val y = 0.2126 * Oklab.srgbToLinear(rgb[0]) + 0.7152 * Oklab.srgbToLinear(rgb[1]) + 0.0722 * Oklab.srgbToLinear(rgb[2])
        return Tok(lch.l, lch.c, if (lch.c < 0.002) h else lch.h, doubleArrayOf(l, c, h), rgb, lab, y, alpha)
    }

    fun fromArgb(argb: Int): Tok = Oklab.lchFromArgb(argb).let { mk(it.l, it.c, it.h) }

    /** A translucent token composited over its background in sRGB, as browsers do. */
    fun over(fg: Tok, bg: Tok): Tok {
        if (fg.a >= 1) return fg
        val rgb = IntArray(3) { (fg.rgb[it] * fg.a + bg.rgb[it] * (1 - fg.a)).roundToInt() }
        val lab = Oklab.rgbToOklab(rgb[0], rgb[1], rgb[2])
        val lch = Oklab.labToLch(lab[0], lab[1], lab[2])
        return mk(lch.l, lch.c, lch.h)
    }

    /** WCAG 2 contrast ratio. */
    fun contrast(fg: Tok, bg: Tok): Double {
        val f = over(fg, bg)
        val hi = max(f.y, bg.y)
        val lo = min(f.y, bg.y)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The largest chroma that is inside sRGB at this lightness and hue (22 bisection steps over 0 to 0.4). */
    fun maxChroma(l: Double, h: Double): Double {
        var lo = 0.0
        var hi = 0.4
        repeat(22) {
            val mid = (lo + hi) / 2
            if (inGamut(lchLin(l, mid, h))) lo = mid else hi = mid
        }
        return lo
    }

    /** Keeps a requested chroma inside the sRGB edge by the gamut margin: the edge is where a colour turns neon. */
    fun inside(l: Double, c: Double, h: Double): Double = min(c, gamutMargin * maxChroma(l, h))
}
