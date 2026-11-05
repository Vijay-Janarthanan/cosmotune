package com.cosmotune.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the cover palette tests share: the test's own colour maths (WCAG contrast, OKLab and OKLCH, the sRGB gamut), which
 * does not use the code under test, the covers, and the rails every palette must hold.
 */
abstract class ThemeTestBase {

    // --- the test's own colour maths ---------------------------------------------------------------

    protected fun red(c: Int) = (c shr 16) and 0xFF
    protected fun green(c: Int) = (c shr 8) and 0xFF
    protected fun blue(c: Int) = c and 0xFF
    protected fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    protected fun hex(c: Int) = "#" + (c and 0xFFFFFF).toString(16).padStart(6, '0')

    /** WCAG 2.x: the 0.03928 knee and the 0.2126 / 0.7152 / 0.0722 weights. */
    private fun wcagLinear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    protected fun luminance(c: Int) = 0.2126 * wcagLinear(red(c)) + 0.7152 * wcagLinear(green(c)) + 0.0722 * wcagLinear(blue(c))

    protected fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** A translucent colour (alpha in its top byte) composited over an opaque one, in sRGB, rounded to 8 bits. */
    protected fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(shift: Int) = ((((fg shr shift) and 0xFF) * a) + (((bg shr shift) and 0xFF) * (1 - a))).roundToInt()
        return rgb(ch(16), ch(8), ch(0))
    }

    private fun srgbToLinear(channel8: Int): Double {
        val x = channel8 / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    /** OKLab (L, a, b) of an opaque colour. */
    protected fun oklab(c: Int): DoubleArray {
        val r = srgbToLinear(red(c))
        val g = srgbToLinear(green(c))
        val b = srgbToLinear(blue(c))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    protected fun oklabL(c: Int) = oklab(c)[0]

    /** OKLCH: lightness, chroma, hue in degrees. */
    protected fun oklch(c: Int): Triple<Double, Double, Double> {
        val lab = oklab(c)
        return Triple(lab[0], hypot(lab[1], lab[2]), ((atan2(lab[2], lab[1]) * 180.0 / PI) + 360.0) % 360.0)
    }

    protected fun lightness(c: Int) = oklch(c).first
    protected fun chromaOf(c: Int) = oklch(c).second
    protected fun hueOf(c: Int) = oklch(c).third

    /** Whether OKLCH (l, c, h) is inside the sRGB cube (with the reference's slack of 0.0001). */
    private fun inGamut(l: Double, c: Double, h: Double): Boolean {
        val a = c * cos(h * PI / 180.0)
        val b = c * sin(h * PI / 180.0)
        val lc = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val mc = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val sc = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val lin = doubleArrayOf(
            4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
            -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
            -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc,
        )
        return lin.all { it >= -0.0001 && it <= 1.0001 }
    }

    /** The largest chroma inside sRGB at this lightness and hue. */
    protected fun maxChroma(l: Double, h: Double): Double {
        var lo = 0.0
        var hi = 0.4
        repeat(30) {
            val mid = (lo + hi) / 2
            if (inGamut(l, mid, h)) lo = mid else hi = mid
        }
        return lo
    }

    protected fun hueDistance(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    /** Hue, saturation and value (0..1) to ARGB: a different route from the engine's OKLCH, for building test covers. */
    protected fun hsv(hue: Double, saturation: Double, value: Double): Int {
        val chroma = value * saturation
        val sector = (hue / 60.0) % 6.0
        val x = chroma * (1.0 - abs(sector % 2.0 - 1.0))
        val m = value - chroma
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** A seed colour of this OKLCH (lowered into sRGB by chroma, found by bisection, hue and lightness kept). */
    protected fun lchColour(l: Double, c: Double, h: Double): Int {
        var lo = 0.0
        var hi = c
        if (inGamut(l, c, h)) lo = c
        else repeat(30) {
            val mid = (lo + hi) / 2
            if (inGamut(l, mid, h)) lo = mid else hi = mid
        }
        val a = lo * cos(h * PI / 180.0)
        val b = lo * sin(h * PI / 180.0)
        val lc = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val mc = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val sc = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        fun enc(v: Double): Int {
            val x = v.coerceIn(0.0, 1.0)
            return ((if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055) * 255).roundToInt()
        }
        return rgb(
            enc(4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc),
            enc(-1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc),
            enc(-0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc),
        )
    }

    // --- the covers --------------------------------------------------------------------------------

    protected val red = rgb(255, 0, 0)
    protected val blueColor = rgb(0, 0, 255)
    protected val yellow = rgb(255, 255, 0)
    protected val white = rgb(255, 255, 255)
    protected val black = rgb(0, 0, 0)
    protected val apricot = 0xFFFFB98F.toInt()

    protected fun solid(argb: Int, w: Int = 16, h: Int = 16) = IntArray(w * h) { argb }

    protected fun palette(
        pixels: IntArray,
        w: Int,
        h: Int,
        dark: Boolean = true,
        spec: CoverPaletteSpec = CoverPaletteSpec(),
    ) = CoverPalette.fromPixels(pixels, w, h, dark, spec)

    protected fun solidPalette(argb: Int, dark: Boolean = true) = palette(solid(argb), 16, 16, dark)

    /** A large blue field with a small orange logo in one corner (64 of 2304 pixels, 2.8 percent). */
    protected fun blueFieldOrangeLogo(): IntArray {
        val field = rgb(30, 80, 200)
        val logo = rgb(255, 140, 30)
        return IntArray(48 * 48) { i ->
            val x = i % 48
            val y = i / 48
            if (x in 36..43 && y in 36..43) logo else field
        }
    }

    protected fun greyRamp(): IntArray = IntArray(32 * 32) { i ->
        val v = (i * 255) / (32 * 32 - 1)
        rgb(v, v, v)
    }

    /** Left half deep pink, right half teal, both neon. */
    protected fun neonSplit(): IntArray = IntArray(40 * 40) { i -> if (i % 40 < 20) rgb(255, 20, 147) else rgb(0, 229, 204) }

    /** How many distinct colours the engine sees in a cover, counted at 5 bits per channel after sampling. */
    protected fun distinctBins(pixels: IntArray, w: Int, h: Int): Int =
        CoverPalette.sample(pixels, w, h).map { ((red(it) shr 3) shl 10) or ((green(it) shr 3) shl 5) or (blue(it) shr 3) }.toSet().size

    protected fun scaled(base: Int, factor: Double, noise: Int, random: Random): Int {
        fun channel(v: Int) = (v * factor + random.nextInt(-noise, noise + 1)).roundToInt().coerceIn(0, 255)
        return rgb(channel(red(base)), channel(green(base)), channel(blue(base)))
    }

    /**
     * A 64 x 64 blue gradient (dark navy on the left, light blue on the right, a little lighter towards the bottom)
     * with a few levels of noise on every channel, and an orange logo in the top-right corner that covers 121 of 4096
     * pixels (2.95 percent) plus its own noise. Fixed random seed: the same cover on every machine.
     */
    protected fun noisyBlueGradientWithOrangeLogo(logoSide: Int = 11): IntArray {
        val random = Random(2026)
        return IntArray(64 * 64) { i ->
            val x = i % 64
            val y = i / 64
            if (x >= 64 - logoSide && y < logoSide) {
                scaled(rgb(255, 140, 30), 1.0, 6, random)
            } else {
                val t = x / 63.0
                val base = rgb((20 + 50 * t).roundToInt(), (70 + 70 * t).roundToInt(), (180 + 65 * t).roundToInt())
                scaled(base, 0.9 + 0.2 * (y / 63.0), 6, random)
            }
        }
    }

    /**
     * A 64 x 64 photo-like cover: a field with a vertical light gradient, an object (a shaded disc in the middle) that
     * covers [objectShare] of the pixels, and per-pixel noise on all three channels.
     */
    protected fun photoLike(field: Int, obj: Int, objectShare: Double, seed: Int): IntArray {
        val random = Random(seed)
        val radius = sqrt(objectShare * 64 * 64 / PI)
        return IntArray(64 * 64) { i ->
            val x = i % 64
            val y = i / 64
            val dx = x - 31.5
            val dy = y - 31.5
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= radius) {
                scaled(obj, 0.8 + 0.3 * (1.0 - distance / radius), 14, random)
            } else {
                scaled(field, 0.6 + 0.55 * (y / 63.0), 14, random)
            }
        }
    }

    /** A 64 x 64 cover of exactly [bins] distinct 5-bit colours of one blue family, every colour on the same number of pixels. */
    protected fun coverWithBins(bins: Int): IntArray {
        val lattice = mutableListOf<Int>()
        for (r in 2..6) for (g in 6..13) for (b in 20..28) lattice += rgb((r shl 3) + 4, (g shl 3) + 4, (b shl 3) + 4)
        val colours = lattice.take(bins)
        return IntArray(64 * 64) { i -> colours[i % bins] }
    }

    // --- the rails every palette must hold ---------------------------------------------------------

    protected fun assertAtLeast(minimum: Double, actual: Double, what: String) {
        assertTrue(actual >= minimum, "$what: contrast $actual is below $minimum")
    }

    protected fun assertAbout(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(actual - expected) <= tolerance, "$what: $actual, expected $expected plus or minus $tolerance")

    /** Every opaque colour of a palette. */
    protected fun opaqueFields(c: CoverColors): List<Pair<String, Int>> = listOf(
        "seed" to c.seed, "ambient" to c.ambient, "backgroundTop" to c.backgroundTop, "backgroundMid" to c.backgroundMid,
        "backgroundBottom" to c.backgroundBottom, "surface" to c.surface, "surfaceHigh" to c.surfaceHigh,
        "surfaceVariant" to c.surfaceVariant, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary,
        "textTertiary" to c.textTertiary, "accent" to c.accent, "accentStrong" to c.accentStrong, "onAccent" to c.onAccent,
        "buttonGradientStart" to c.buttonGradientStart, "buttonGradientEnd" to c.buttonGradientEnd,
        "accentContainer" to c.accentContainer, "onAccentContainer" to c.onAccentContainer, "glow" to c.glow, "scrim" to c.scrim,
    )

    /** Grounds the text can sit on: the three background stops, the ambient light and the three surface tiers. */
    protected fun grounds(c: CoverColors) = listOf(
        c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.ambient, c.surface, c.surfaceHigh, c.surfaceVariant,
    )

    /**
     * The designer's rails (01-dynamic-colour.md, section 3), all checked on the final 8-bit colours.
     * [checkProgress]: the accent against the track composited over the bottom stop (3.0); the reference logs it rather
     * than repairing it, so it is a property of the numbers, not of a loop.
     */
    protected fun assertRails(name: String, c: CoverColors, dark: Boolean, checkProgress: Boolean = true) {
        opaqueFields(c).forEach { (field, colour) -> assertEquals(0xFF, colour ushr 24, "$name: $field is not opaque") }
        listOf("shadow" to c.shadow, "track" to c.track, "outline" to c.outline).forEach { (field, colour) ->
            assertTrue((colour ushr 24) in 1..254, "$name: $field should be translucent, alpha ${colour ushr 24}")
        }
        val all = grounds(c)

        // Text on every ground, 4.5:1.
        listOf("textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary, "textTertiary" to c.textTertiary, "accentStrong" to c.accentStrong)
            .forEach { (field, fg) ->
                all.forEach { assertAtLeast(4.5, contrast(fg, it), "$name $field on ${hex(it)}") }
            }
        // The accent is an interface part (3:1) on the surfaces (dark: the bottom stop and the three tiers; light: every ground).
        val accentGrounds = if (dark) listOf(c.backgroundBottom, c.surface, c.surfaceHigh, c.surfaceVariant) else all
        accentGrounds.forEach { assertAtLeast(3.0, contrast(c.accent, it), "$name accent on ${hex(it)}") }
        // The text on the buttons, on both ends of the gradient; on the accent container.
        listOf(c.buttonGradientStart, c.buttonGradientEnd).forEach { assertAtLeast(4.5, contrast(c.onAccent, it), "$name onAccent on ${hex(it)}") }
        assertAtLeast(4.5, contrast(c.onAccentContainer, c.accentContainer), "$name onAccentContainer on accentContainer")
        if (checkProgress) {
            val track = over(c.track, c.backgroundBottom)
            assertAtLeast(3.0, contrast(c.accent, track), "$name accent on its track over the bottom stop")
        }

        val stops = listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.ambient)
        if (dark) {
            // The darkness rail: Y at most 0.030 and OKLCH L at most 0.30 for every background stop and the ambient light.
            stops.forEach {
                assertTrue(luminance(it) <= 0.030 + 1e-9, "$name: ground ${hex(it)} has luminance ${luminance(it)}, over 0.030")
                assertTrue(lightness(it) <= 0.30 + 1e-6, "$name: ground ${hex(it)} has L ${lightness(it)}, over 0.30")
            }
            // The old, weaker cap (relative luminance 0.12) on every ground including the surface tiers.
            grounds(c).forEach { assertTrue(luminance(it) <= 0.12, "$name: ground ${hex(it)} is brighter than 0.12") }
        } else {
            // The lightness floor of the light theme.
            stops.forEach { assertTrue(lightness(it) >= 0.93 - 1e-6, "$name: ground ${hex(it)} has L ${lightness(it)}, under 0.93") }
        }
    }

    protected fun assertRailsBothThemes(name: String, pixels: IntArray, w: Int, h: Int): Pair<CoverColors, CoverColors> {
        val dark = palette(pixels, w, h, dark = true)
        val light = palette(pixels, w, h, dark = false)
        assertRails("$name (dark)", dark, dark = true)
        assertRails("$name (light)", light, dark = false)
        return dark to light
    }
}
