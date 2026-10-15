package com.cosmotune.theme

import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The cover palette: one colour is picked from the cover and every other colour is derived from it, calm
 * (never too bright) and readable (WCAG contrast). The expectations are written by hand. The contrast, luminance,
 * tone and OKLab functions below are the test's own and do not use the code under test.
 */
class CoverPaletteTest {

    // --- the test's own colour maths ---------------------------------------------------------------

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** WCAG 2.x: the 0.03928 knee and the 0.2126 / 0.7152 / 0.0722 weights. */
    private fun linear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Int) = 0.2126 * linear(red(c)) + 0.7152 * linear(green(c)) + 0.0722 * linear(blue(c))

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** CIE L* (the "tone" of HCT) from the relative luminance. */
    private fun tone(c: Int): Double {
        val y = luminance(c)
        val f = if (y > 216.0 / 24389.0) cbrt(y) else (24389.0 / 27.0 * y + 16.0) / 116.0
        return 116.0 * f - 16.0
    }

    /** The L of OKLab (Ottosson's matrices, applied to linear sRGB). */
    private fun oklabL(c: Int): Double {
        fun lin(ch: Int): Double {
            val x = ch / 255.0
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(red(c))
        val g = lin(green(c))
        val b = lin(blue(c))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    }

    private fun hueDistance(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    /** Hue, saturation and value (0..1) to ARGB: a different route from the code's HCT, for building test covers. */
    private fun hsv(hue: Double, saturation: Double, value: Double): Int {
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

    // --- the covers --------------------------------------------------------------------------------

    private val red = rgb(255, 0, 0)
    private val blueColor = rgb(0, 0, 255)
    private val yellow = rgb(255, 255, 0)
    private val white = rgb(255, 255, 255)
    private val black = rgb(0, 0, 0)
    private val apricot = 0xFFFFB98F.toInt()

    private fun solid(argb: Int, w: Int = 16, h: Int = 16) = IntArray(w * h) { argb }

    private fun palette(
        pixels: IntArray,
        w: Int,
        h: Int,
        dark: Boolean = true,
        spec: CoverPaletteSpec = CoverPaletteSpec(),
    ) = CoverPalette.fromPixels(pixels, w, h, dark, spec)

    private fun solidPalette(argb: Int, dark: Boolean = true) = palette(solid(argb), 16, 16, dark)

    /** A large blue field with a small orange logo in one corner (64 of 2304 pixels, 2.8 percent). */
    private fun blueFieldOrangeLogo(): IntArray {
        val field = rgb(30, 80, 200)
        val logo = rgb(255, 140, 30)
        return IntArray(48 * 48) { i ->
            val x = i % 48
            val y = i / 48
            if (x in 36..43 && y in 36..43) logo else field
        }
    }

    private fun greyRamp(): IntArray = IntArray(32 * 32) { i ->
        val v = (i * 255) / (32 * 32 - 1)
        rgb(v, v, v)
    }

    /** Left half deep pink, right half teal, both neon. */
    private fun neonSplit(): IntArray = IntArray(40 * 40) { i -> if (i % 40 < 20) rgb(255, 20, 147) else rgb(0, 229, 204) }

    // --- the rails every palette must hold ---------------------------------------------------------

    private fun assertAtLeast(minimum: Double, actual: Double, what: String) {
        assertTrue(actual >= minimum, "$what: contrast $actual is below $minimum")
    }

    private fun assertRails(name: String, c: CoverColors, dark: Boolean) {
        val every = listOf(
            c.seed, c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.surface, c.surfaceVariant, c.accent,
            c.accentStrong, c.onAccent, c.buttonGradientStart, c.buttonGradientEnd, c.textPrimary, c.textSecondary, c.glow,
        )
        every.forEachIndexed { i, colour -> assertEquals(0xFF, colour ushr 24, "$name: colour #$i is not opaque") }

        // Primary text on every background stop and on both surfaces.
        listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.surface, c.surfaceVariant).forEach {
            assertAtLeast(4.5, contrast(c.textPrimary, it), "$name textPrimary on #${it.toUInt().toString(16)}")
        }
        // Secondary text: the brief names the surface and the middle stop; the palette holds it on all of them.
        listOf(c.surface, c.backgroundMid, c.backgroundTop, c.backgroundBottom, c.surfaceVariant).forEach {
            assertAtLeast(4.5, contrast(c.textSecondary, it), "$name textSecondary on #${it.toUInt().toString(16)}")
        }
        // Text and icons on the accent and on both ends of the button gradient.
        listOf(c.accent, c.buttonGradientStart, c.buttonGradientEnd).forEach {
            assertAtLeast(4.5, contrast(c.onAccent, it), "$name onAccent on #${it.toUInt().toString(16)}")
        }
        // The accent is a user interface component on the surface.
        assertAtLeast(3.0, contrast(c.accent, c.surface), "$name accent on surface")

        if (dark) {
            listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.surface, c.surfaceVariant).forEach {
                assertTrue(luminance(it) <= 0.12, "$name: dark background colour #${it.toUInt().toString(16)} has luminance ${luminance(it)}")
            }
        }
        // "Not too bright": the accent family stays at or below tone 84 (half a tone of slack for 8-bit rounding).
        listOf(c.accent, c.accentStrong, c.buttonGradientStart, c.buttonGradientEnd, c.glow).forEach {
            assertTrue(tone(it) <= 84.5, "$name: accent colour #${it.toUInt().toString(16)} has tone ${tone(it)}")
        }
    }

    private fun assertRailsBothThemes(name: String, pixels: IntArray, w: Int, h: Int): Pair<CoverColors, CoverColors> {
        val dark = palette(pixels, w, h, dark = true)
        val light = palette(pixels, w, h, dark = false)
        assertRails("$name (dark)", dark, dark = true)
        assertRails("$name (light)", light, dark = false)
        return dark to light
    }

    // --- single covers -----------------------------------------------------------------------------

    @Test
    fun solidRedKeepsItsHue() {
        val (dark, light) = assertRailsBothThemes("solid red", solid(red), 16, 16)
        assertFalse(dark.isFallback)
        assertFalse(light.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(seedHue in 20.0..35.0, "the seed hue of pure red is about 27, was $seedHue")
        assertTrue(hueDistance(Hct.fromInt(dark.accent).hue, seedHue) <= 4.0, "the accent keeps the seed's hue")
        assertTrue(hueDistance(Hct.fromInt(light.accent).hue, seedHue) <= 4.0, "the light accent keeps the seed's hue")
    }

    @Test
    fun solidBlueKeepsItsHue() {
        val (dark, _) = assertRailsBothThemes("solid blue", solid(blueColor), 16, 16)
        assertFalse(dark.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(seedHue in 275.0..290.0, "the seed hue of pure blue is about 283, was $seedHue")
        assertTrue(hueDistance(Hct.fromInt(dark.accent).hue, seedHue) <= 4.0, "the accent keeps the seed's hue")
    }

    @Test
    fun aLargeBlueFieldWithASmallOrangeLogoSeedsFromTheBlue() {
        val (dark, _) = assertRailsBothThemes("blue field, orange logo", blueFieldOrangeLogo(), 48, 48)
        assertFalse(dark.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(hueDistance(seedHue, 265.0) <= 25.0, "the seed should be the blue (hue near 265), hue was $seedHue")
        // The orange of the logo sits near hue 55: nowhere near.
        assertTrue(hueDistance(seedHue, 55.0) > 90.0, "the seed must not be the orange logo")
    }

    @Test
    fun aGreyscaleCoverGivesTheFallbackPalette() {
        val (dark, light) = assertRailsBothThemes("greyscale", greyRamp(), 32, 32)
        assertTrue(dark.isFallback)
        assertTrue(light.isFallback)
        assertEquals(apricot, dark.seed)
    }

    @Test
    fun aWhiteCoverGivesTheFallbackPalette() {
        val (dark, light) = assertRailsBothThemes("white", solid(white), 16, 16)
        assertTrue(dark.isFallback)
        assertTrue(light.isFallback)
        assertEquals(apricot, dark.seed)
    }

    @Test
    fun aBlackCoverGivesTheFallbackPalette() {
        val (dark, light) = assertRailsBothThemes("black", solid(black), 16, 16)
        assertTrue(dark.isFallback)
        assertTrue(light.isFallback)
        assertEquals(apricot, dark.seed)
    }

    @Test
    fun aCoverThatIsAlmostGreyCountsAsNoColour() {
        // (128, 126, 126): a grey with a trace of red, far below the seed chroma threshold.
        val palette = palette(solid(rgb(128, 126, 126)), 16, 16)
        assertTrue(palette.isFallback)
    }

    @Test
    fun aPureYellowCoverKeepsItsHueButStaysCalm() {
        val (dark, light) = assertRailsBothThemes("pure yellow", solid(yellow), 16, 16)
        assertFalse(dark.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(seedHue in 100.0..120.0, "the seed hue of pure yellow is about 110, was $seedHue")
        assertTrue(hueDistance(Hct.fromInt(dark.accent).hue, seedHue) <= 4.0, "the accent is still yellow")
        assertTrue(tone(dark.accent) <= 84.5, "the accent tone is capped at 84, was ${tone(dark.accent)}")
        assertTrue(tone(light.accent) <= 84.5)
        listOf(dark.backgroundTop, dark.backgroundMid, dark.backgroundBottom, dark.surface, dark.surfaceVariant).forEach {
            assertTrue(luminance(it) <= 0.12, "a yellow cover must not make a bright background")
        }
    }

    @Test
    fun aNeonPinkAndTealSplitCoverHoldsTheRails() {
        val (dark, _) = assertRailsBothThemes("neon pink and teal", neonSplit(), 40, 40)
        assertFalse(dark.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        val pink = Hct.fromInt(rgb(255, 20, 147)).hue
        val teal = Hct.fromInt(rgb(0, 229, 204)).hue
        assertTrue(
            hueDistance(seedHue, pink) <= 20.0 || hueDistance(seedHue, teal) <= 20.0,
            "the seed is one of the two colours (pink hue $pink, teal hue $teal), hue was $seedHue",
        )
        assertTrue(Hct.fromInt(dark.seed).chroma > 50.0, "a neon cover gives a vivid seed, not a muddy mix: chroma ${Hct.fromInt(dark.seed).chroma}")
    }

    @Test
    fun aCoverOfTwoFlatColoursSeedsFromOneOfThem() {
        // Flat art has a handful of exact colours. Google's quantiser alone merges such covers into one
        // muddy blend (pink and teal become a dull mauve), so the flat case is read from the colour histogram.
        val c = palette(neonSplit(), 40, 40)
        assertTrue(c.seed == rgb(255, 20, 147) || c.seed == rgb(0, 229, 204), "the seed is #${c.seed.toUInt().toString(16)}")
    }

    @Test
    fun aCoverOfFourFlatColoursSeedsFromOneOfThem() {
        val pink = rgb(255, 20, 147)
        val teal = rgb(0, 229, 204)
        val gold = rgb(255, 193, 7)
        val navy = rgb(26, 35, 126)
        val pixels = IntArray(40 * 40) { i ->
            val left = i % 40 < 20
            val top = i / 40 < 20
            when {
                left && top -> pink
                !left && top -> teal
                left -> gold
                else -> navy
            }
        }
        val c = palette(pixels, 40, 40)
        assertTrue(c.seed in listOf(pink, teal, gold, navy), "the seed is #${c.seed.toUInt().toString(16)}, none of the four colours")
        assertFalse(c.isFallback)
        assertRailsBothThemes("four flat colours", pixels, 40, 40)
    }

    @Test
    fun lightNoiseOnAFlatCoverDoesNotMuddyTheSeed() {
        // The same pink and teal with a few levels of noise on every channel, as a JPEG leaves on flat art.
        val random = Random(5)
        fun noisy(base: Int) = rgb(
            (red(base) + random.nextInt(-2, 3)).coerceIn(0, 255),
            (green(base) + random.nextInt(-2, 3)).coerceIn(0, 255),
            (blue(base) + random.nextInt(-2, 3)).coerceIn(0, 255),
        )
        val pixels = IntArray(40 * 40) { i -> noisy(if (i % 40 < 20) rgb(255, 20, 147) else rgb(0, 229, 204)) }
        val c = palette(pixels, 40, 40)
        val seed = Hct.fromInt(c.seed)
        val pink = Hct.fromInt(rgb(255, 20, 147)).hue
        val teal = Hct.fromInt(rgb(0, 229, 204)).hue
        assertTrue(seed.chroma > 50.0, "the seed is vivid, chroma ${seed.chroma}")
        assertTrue(hueDistance(seed.hue, pink) <= 12.0 || hueDistance(seed.hue, teal) <= 12.0, "seed hue ${seed.hue}")
        assertRails("noisy flat cover", c, dark = true)
    }

    // --- the numbers of the design -----------------------------------------------------------------

    @Test
    fun theDarkThemeUsesTheDesignTones() {
        val c = solidPalette(blueColor, dark = true)
        fun assertTone(expected: Double, colour: Int, what: String) =
            assertTrue(abs(tone(colour) - expected) <= 1.5, "$what: tone ${tone(colour)}, expected $expected")
        assertTone(15.0, c.backgroundTop, "backgroundTop")
        assertTone(10.0, c.backgroundMid, "backgroundMid")
        assertTone(6.0, c.backgroundBottom, "backgroundBottom")
        assertTone(10.0, c.surface, "surface")
        assertTone(17.0, c.surfaceVariant, "surfaceVariant")
        assertTone(78.0, c.accent, "accent")
        assertTone(68.0, c.accentStrong, "accentStrong")
        assertTone(82.0, c.buttonGradientStart, "buttonGradientStart")
        assertTone(70.0, c.buttonGradientEnd, "buttonGradientEnd")
        assertTone(96.0, c.textPrimary, "textPrimary")
        assertTone(78.0, c.textSecondary, "textSecondary")
        assertTone(60.0, c.glow, "glow")
        assertTone(8.0, c.onAccent, "onAccent")
    }

    @Test
    fun theLightThemeUsesTheDesignTones() {
        val c = solidPalette(blueColor, dark = false)
        fun assertTone(expected: Double, colour: Int, what: String) =
            assertTrue(abs(tone(colour) - expected) <= 1.5, "$what: tone ${tone(colour)}, expected $expected")
        assertTone(98.0, c.backgroundTop, "backgroundTop")
        assertTone(96.0, c.backgroundMid, "backgroundMid")
        assertTone(94.0, c.backgroundBottom, "backgroundBottom")
        assertTone(99.0, c.surface, "surface")
        assertTone(93.0, c.surfaceVariant, "surfaceVariant")
        assertTone(44.0, c.accent, "accent")
        assertTone(38.0, c.accentStrong, "accentStrong")
        assertTone(46.0, c.buttonGradientStart, "buttonGradientStart")
        assertTone(38.0, c.buttonGradientEnd, "buttonGradientEnd")
        assertTone(10.0, c.textPrimary, "textPrimary")
        assertTone(35.0, c.textSecondary, "textSecondary")
        assertTone(70.0, c.glow, "glow")
        assertEquals(white, c.onAccent, "the on-accent colour of the light theme is white")
    }

    @Test
    fun theBackgroundChromaIsCappedByTheDesign() {
        // Pure red has a seed chroma of about 113, so the caps (28, 22, 16) are what limits the backgrounds.
        val c = solidPalette(red, dark = true)
        assertTrue(Hct.fromInt(c.backgroundTop).chroma <= 28.0 + 3.0, "top chroma ${Hct.fromInt(c.backgroundTop).chroma}")
        assertTrue(Hct.fromInt(c.backgroundMid).chroma <= 22.0 + 3.0, "mid chroma ${Hct.fromInt(c.backgroundMid).chroma}")
        assertTrue(Hct.fromInt(c.backgroundBottom).chroma <= 16.0 + 3.0, "bottom chroma ${Hct.fromInt(c.backgroundBottom).chroma}")
        // And the gradient is a gradient: top lighter than middle, middle lighter than bottom.
        assertTrue(tone(c.backgroundTop) > tone(c.backgroundMid))
        assertTrue(tone(c.backgroundMid) > tone(c.backgroundBottom))
    }

    @Test
    fun theButtonGradientTurnsTheHueEitherWay() {
        val c = solidPalette(blueColor, dark = true)
        val seedHue = Hct.fromInt(c.seed).hue
        val start = Hct.fromInt(c.buttonGradientStart).hue
        val end = Hct.fromInt(c.buttonGradientEnd).hue
        assertTrue(hueDistance(start, seedHue - 14.0) <= 6.0, "start hue $start, seed hue $seedHue")
        assertTrue(hueDistance(end, seedHue + 14.0) <= 6.0, "end hue $end, seed hue $seedHue")
    }

    // --- the sweep ---------------------------------------------------------------------------------

    @Test
    fun theRailsHoldAcrossEveryHueAndChromaOfASyntheticCover() {
        var colourful = 0
        var total = 0
        for (hue in 0..345 step 15) {
            for (saturation in listOf(0.25, 0.6, 1.0)) { // low, mid and high chroma
                for (value in listOf(0.45, 0.9)) {
                    val argb = hsv(hue.toDouble(), saturation, value)
                    val name = "hue $hue saturation $saturation value $value (#${argb.toUInt().toString(16)})"
                    val (dark, light) = assertRailsBothThemes(name, solid(argb), 16, 16)
                    total++
                    if (!dark.isFallback) {
                        colourful++
                        // The seed is the cover's own colour: the pipeline did not wander off.
                        assertTrue(hueDistance(Hct.fromInt(dark.seed).hue, Hct.fromInt(argb).hue) <= 3.0, "$name: seed hue")
                        // The accent keeps the seed's hue when the screen can show it with any real chroma: gamut is
                        // met by lowering chroma, not by clipping channels (which would turn the hue).
                        val accent = Hct.fromInt(dark.accent)
                        if (accent.chroma >= 12.0) {
                            assertTrue(hueDistance(accent.hue, Hct.fromInt(dark.seed).hue) <= 4.0, "$name: accent hue ${accent.hue}")
                        }
                        val lightAccent = Hct.fromInt(light.accent)
                        if (lightAccent.chroma >= 12.0) {
                            assertTrue(hueDistance(lightAccent.hue, Hct.fromInt(light.seed).hue) <= 4.0, "$name: light accent hue")
                        }
                    } else {
                        assertEquals(apricot, dark.seed, "$name: a cover with no colour uses the fallback seed")
                    }
                }
            }
        }
        assertEquals(144, total)
        assertTrue(colourful >= 120, "the sweep must mostly exercise real seeds, only $colourful of $total were colourful")
    }

    @Test
    fun randomNoiseOfEverySizeNeverBreaksTheRails() {
        val random = Random(20261015)
        repeat(60) { n ->
            val w = random.nextInt(1, 81)
            val h = random.nextInt(1, 81)
            val pixels = IntArray(w * h) { random.nextInt() } // random alpha too
            val (dark, light) = assertRailsBothThemes("noise #$n (${w}x$h)", pixels, w, h)
            assertEquals(dark.isFallback, light.isFallback, "noise #$n: the seed does not depend on the theme")
        }
    }

    // --- determinism -------------------------------------------------------------------------------

    @Test
    fun theSameCoverGivesTheSamePaletteEveryTime() {
        val pixels = blueFieldOrangeLogo()
        val first = palette(pixels, 48, 48)
        val second = palette(pixels, 48, 48)
        assertEquals(first, second)
    }

    @Test
    fun anotherCoverInBetweenChangesNothing() {
        val a = palette(neonSplit(), 40, 40)
        palette(solid(yellow), 16, 16)
        palette(greyRamp(), 32, 32)
        val b = palette(neonSplit(), 40, 40)
        assertEquals(a, b)
    }

    @Test
    fun theCoverArrayIsNotChanged() {
        val pixels = blueFieldOrangeLogo()
        val copy = pixels.copyOf()
        palette(pixels, 48, 48)
        assertContentEquals(copy, pixels)
    }

    // --- the fallback ------------------------------------------------------------------------------

    @Test
    fun theFallbackSeedCanBeChosenByTheCaller() {
        val custom = 0xFF336699.toInt()
        val c = CoverPalette.fromPixels(solid(white), 16, 16, fallbackSeed = custom)
        assertTrue(c.isFallback)
        assertEquals(custom, c.seed)
        assertRails("custom fallback", c, dark = true)
    }

    @Test
    fun aFallbackSeedWithTransparencyIsMadeOpaque() {
        val c = CoverPalette.fromPixels(solid(white), 16, 16, fallbackSeed = 0x00336699)
        assertEquals(0xFF336699.toInt(), c.seed)
    }

    @Test
    fun theFallbackPaletteIsStillCalmAndReadable() {
        // The apricot of the logo is bright (tone 80): the dark theme must still not put it behind the text.
        val c = solidPalette(white, dark = true)
        assertTrue(c.isFallback)
        listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom).forEach { assertTrue(tone(it) <= 20.0) }
    }

    // --- reading the pixels ------------------------------------------------------------------------

    @Test
    fun anEmptyCoverGivesTheFallback() {
        val c = palette(IntArray(0), 0, 0)
        assertTrue(c.isFallback)
        assertRails("empty", c, dark = true)
        assertTrue(palette(IntArray(0), 5, 5).isFallback, "no pixels for a 5 x 5 cover")
        assertTrue(palette(IntArray(0), 0, 0, dark = false).isFallback)
    }

    @Test
    fun nonsenseSizesGiveTheFallbackAndDoNotCrash() {
        assertTrue(palette(solid(red, 4, 4), -4, 4).isFallback, "a negative width")
        assertTrue(palette(solid(red, 4, 4), 4, -4).isFallback, "a negative height")
        assertTrue(palette(solid(red, 4, 4), 0, 4).isFallback, "a zero width")
        assertTrue(palette(solid(red, 4, 4), 8, 8).isFallback, "an array shorter than width x height")
        assertTrue(palette(solid(red, 4, 4), Int.MAX_VALUE, Int.MAX_VALUE).isFallback, "a size that overflows")
    }

    @Test
    fun aSingleRedPixelIsACover() {
        val c = palette(intArrayOf(red), 1, 1)
        assertFalse(c.isFallback)
        assertTrue(Hct.fromInt(c.seed).hue in 20.0..35.0)
        assertRails("1x1 red", c, dark = true)
    }

    @Test
    fun aSinglePixelColumnAndRowAreCovers() {
        val column = palette(IntArray(100) { blueColor }, 1, 100)
        val row = palette(IntArray(100) { blueColor }, 100, 1)
        assertFalse(column.isFallback)
        assertFalse(row.isFallback)
        assertEquals(column, row)
        assertRails("1x100 blue", column, dark = true)
    }

    @Test
    fun aVeryLargeCoverIsReadFromAtMost64BySixtyFourSamples() {
        assertEquals(64 * 64, CoverPalette.sample(IntArray(1000 * 1000) { red }, 1000, 1000).size)
        assertEquals(64 * 64, CoverPalette.sample(IntArray(200 * 100) { red }, 200, 100).size)
        assertEquals(1 * 64, CoverPalette.sample(IntArray(100) { red }, 1, 100).size)
        assertEquals(64 * 1, CoverPalette.sample(IntArray(100) { red }, 100, 1).size)
        assertEquals(1, CoverPalette.sample(intArrayOf(red), 1, 1).size)
        assertEquals(9, CoverPalette.sample(IntArray(9) { red }, 3, 3).size)
        val exact = IntArray(64 * 64) { 0xFF000000.toInt() or it }
        assertContentEquals(exact, CoverPalette.sample(exact, 64, 64), "a cover that is already 64 x 64 is read as it is")
        val custom = CoverPaletteSpec(maxSampleSide = 8)
        assertEquals(8 * 8, CoverPalette.sample(IntArray(100 * 100) { red }, 100, 100, custom).size)
    }

    @Test
    fun emptyAndInconsistentArraysSampleToNothing() {
        assertEquals(0, CoverPalette.sample(IntArray(0), 0, 0).size)
        assertEquals(0, CoverPalette.sample(IntArray(0), 3, 3).size)
        assertEquals(0, CoverPalette.sample(IntArray(4) { red }, 3, 3).size)
        assertEquals(0, CoverPalette.sample(IntArray(9) { red }, -3, 3).size)
        assertEquals(0, CoverPalette.sample(IntArray(9) { red }, 3, 0).size)
    }

    @Test
    fun sampledPixelsComeOutOpaque() {
        val sampled = CoverPalette.sample(intArrayOf(0x80336699.toInt()), 1, 1)
        assertContentEquals(intArrayOf(0xFF336699.toInt()), sampled)
    }

    @Test
    fun pixelsBelowHalfAlphaAreDropped() {
        // Alpha 0, 127, 128 and 255: the first two are gone, 128 is kept.
        val pixels = intArrayOf(0x00FF0000, 0x7F00FF00, 0x800000FF.toInt(), 0xFFFFFF00.toInt())
        val sampled = CoverPalette.sample(pixels, 4, 1)
        assertContentEquals(intArrayOf(0xFF0000FF.toInt(), 0xFFFFFF00.toInt()), sampled)
    }

    @Test
    fun aFullyTransparentCoverGivesTheFallback() {
        val c = palette(IntArray(16 * 16) { 0x00FF0000 }, 16, 16)
        assertTrue(c.isFallback, "a transparent red cover has no colour to take")
    }

    @Test
    fun transparentPixelsDoNotChooseTheSeed() {
        // The left half is red but fully transparent; the opaque right half is blue.
        val pixels = IntArray(20 * 20) { i -> if (i % 20 < 10) 0x00FF0000 else blueColor }
        val c = palette(pixels, 20, 20)
        assertFalse(c.isFallback)
        assertTrue(hueDistance(Hct.fromInt(c.seed).hue, 283.0) <= 12.0, "the seed is the blue, hue ${Hct.fromInt(c.seed).hue}")
    }

    @Test
    fun theSamplerTakesTheCentrePixelOfEachBlock() {
        // 128 columns sampled to 64: block k is columns 2k and 2k+1, and the centre rule takes 2k+1.
        // Even columns red, odd columns blue: the samples must be all blue.
        val pixels = IntArray(128) { x -> if (x % 2 == 0) red else blueColor }
        val sampled = CoverPalette.sample(pixels, 128, 1)
        assertEquals(64, sampled.size)
        assertTrue(sampled.all { it == blueColor }, "nearest sampling at the block centre")
        assertTrue(Hct.fromInt(palette(pixels, 128, 1).seed).hue in 275.0..290.0)
    }

    // --- lerp --------------------------------------------------------------------------------------

    private fun fields(c: CoverColors) = listOf(
        c.seed, c.backgroundTop, c.backgroundMid, c.backgroundBottom, c.surface, c.surfaceVariant, c.accent,
        c.accentStrong, c.onAccent, c.buttonGradientStart, c.buttonGradientEnd, c.textPrimary, c.textSecondary, c.glow,
    )

    @Test
    fun lerpEndpointsAreTheTwoPalettes() {
        val a = solidPalette(red)
        val b = solidPalette(blueColor)
        assertEquals(a, CoverPalette.lerp(a, b, 0f))
        assertEquals(b, CoverPalette.lerp(a, b, 1f))
    }

    @Test
    fun lerpClampsTOutsideZeroToOne() {
        val a = solidPalette(red)
        val b = solidPalette(blueColor)
        assertEquals(a, CoverPalette.lerp(a, b, -0.5f))
        assertEquals(b, CoverPalette.lerp(a, b, 1.5f))
        assertEquals(a, CoverPalette.lerp(a, b, Float.NaN))
    }

    @Test
    fun theMidpointLiesBetweenTheTwoInOklabLightness() {
        val a = solidPalette(red)
        val b = solidPalette(blueColor)
        val mid = CoverPalette.lerp(a, b, 0.5f)
        val before = fields(a)
        val after = fields(b)
        val middle = fields(mid)
        for (i in before.indices) {
            val lo = min(oklabL(before[i]), oklabL(after[i]))
            val hi = max(oklabL(before[i]), oklabL(after[i]))
            val l = oklabL(middle[i])
            // Eight-bit rounding can move L by about 0.002.
            assertTrue(l >= lo - 0.005 && l <= hi + 0.005, "field #$i: L $l is not between $lo and $hi")
            // OKLab is where the straight line is drawn, so the lightness is the mean (not a mix done on the channels).
            assertTrue(abs(l - (oklabL(before[i]) + oklabL(after[i])) / 2.0) <= 0.01, "field #$i: L $l is not the mean")
        }
        assertNotEquals(a, mid)
        assertNotEquals(b, mid)
        middle.forEach { assertEquals(0xFF, it ushr 24, "an interpolated colour is opaque") }
    }

    @Test
    fun lerpOfAPaletteWithItselfIsThatPalette() {
        val a = solidPalette(blueColor)
        assertEquals(a, CoverPalette.lerp(a, a, 0.3f))
    }

    @Test
    fun lerpMovesTheLightnessStepByStep() {
        // A dark palette to a light one: the background gets lighter all the way.
        val dark = solidPalette(blueColor, dark = true)
        val light = solidPalette(blueColor, dark = false)
        var previous = oklabL(dark.backgroundTop)
        for (step in 1..10) {
            val l = oklabL(CoverPalette.lerp(dark, light, step / 10f).backgroundTop)
            assertTrue(l >= previous - 0.004, "step $step: $l after $previous")
            previous = l
        }
    }

    @Test
    fun lerpKeepsEveryColourInGamutWhenTheEndsAreFarApart() {
        // Interpolating in OKLab can leave the sRGB cube between two vivid colours; the result must still be a real colour.
        val a = solidPalette(red)
        val b = solidPalette(blueColor)
        for (step in 0..20) {
            val c = CoverPalette.lerp(a, b, step / 20f)
            fields(c).forEach { assertEquals(0xFF, it ushr 24) }
        }
    }

    @Test
    fun theFallbackFlagFollowsTheNearerEnd() {
        val fallback = solidPalette(white)
        val real = solidPalette(red)
        assertTrue(fallback.isFallback)
        assertFalse(real.isFallback)
        assertTrue(CoverPalette.lerp(fallback, real, 0.25f).isFallback)
        assertFalse(CoverPalette.lerp(fallback, real, 0.75f).isFallback)
    }

    // --- settle ------------------------------------------------------------------------------------

    @Test
    fun settleWithNoPreviousPaletteTakesTheNext() {
        val next = solidPalette(red)
        assertSame(next, CoverPalette.settle(null, next))
    }

    @Test
    fun settleKeepsThePreviousPaletteForAnAlmostIdenticalSeed() {
        // Two tracks of the same album: the same cover, perhaps a different JPEG, a different last bit of colour.
        val previous = palette(solid(rgb(48, 80, 192)), 16, 16)
        val next = palette(solid(rgb(49, 81, 192)), 16, 16)
        assertNotEquals(previous, next, "the two palettes are not identical, or the test proves nothing")
        assertSame(previous, CoverPalette.settle(previous, next))
    }

    @Test
    fun settleKeepsThePreviousPaletteForTheSameCoverAgain() {
        val previous = solidPalette(red)
        val next = solidPalette(red)
        assertNotSame(previous, next)
        assertSame(previous, CoverPalette.settle(previous, next))
    }

    @Test
    fun settleTakesTheNextPaletteForADifferentCover() {
        val previous = solidPalette(red)
        val next = solidPalette(blueColor)
        assertSame(next, CoverPalette.settle(previous, next))
    }

    @Test
    fun settleTakesTheNextPaletteWhenTheThemeChanged() {
        // The same cover and the same seed, but dark and light palettes differ everywhere except the seed.
        val previous = solidPalette(blueColor, dark = true)
        val next = solidPalette(blueColor, dark = false)
        assertEquals(previous.seed, next.seed)
        assertSame(next, CoverPalette.settle(previous, next))
    }

    @Test
    fun settleHonoursTheThreshold() {
        val previous = solidPalette(red)
        val next = solidPalette(blueColor)
        assertSame(next, CoverPalette.settle(previous, next, 0f), "a threshold of zero never keeps the previous")
        assertSame(previous, CoverPalette.settle(previous, next, 1000f), "a huge threshold always keeps it")
        val slightly = palette(solid(rgb(255, 10, 0)), 16, 16)
        assertSame(previous, CoverPalette.settle(previous, slightly, 3f))
        assertSame(slightly, CoverPalette.settle(previous, slightly, 0.0001f))
    }

    @Test
    fun settleAndLerpWorkTogether() {
        // The screen's loop: settle against what is on screen, then glide from there to the result.
        var onScreen: CoverColors? = null
        val covers = listOf(solid(red), solid(red), solid(blueColor), solid(blueColor), solid(yellow))
        val seen = covers.map { cover ->
            val next = CoverPalette.settle(onScreen, palette(cover, 16, 16))
            onScreen = next
            next
        }
        assertSame(seen[0], seen[1])
        assertNotSame(seen[1], seen[2])
        assertSame(seen[2], seen[3])
        assertNotSame(seen[3], seen[4])
        assertEquals(seen[2], CoverPalette.lerp(seen[1], seen[2], 1f))
    }

    // --- the spec: retuning, and the safety rails when it is retuned badly ------------------------

    @Test
    fun theSpecNumbersReachThePalette() {
        val spec = CoverPaletteSpec(darkBackgroundTopTone = 12.0, darkAccentTone = 70.0)
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertTrue(abs(tone(c.backgroundTop) - 12.0) <= 1.5, "backgroundTop tone ${tone(c.backgroundTop)}")
        assertTrue(abs(tone(c.accent) - 70.0) <= 1.5, "accent tone ${tone(c.accent)}")
        assertRails("retuned", c, dark = true)
    }

    @Test
    fun aTextToneThatIsTooDarkIsMovedUpUntilItIsReadable() {
        val spec = CoverPaletteSpec(darkTextPrimaryTone = 40.0, darkTextSecondaryTone = 40.0)
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("bad text tone", c, dark = true)
        assertTrue(tone(c.textPrimary) in 56.0..70.0, "moved up from 40, to ${tone(c.textPrimary)}")
        assertTrue(tone(c.textSecondary) in 56.0..70.0, "moved up from 40, to ${tone(c.textSecondary)}")
    }

    @Test
    fun aLightTextThatIsTooPaleIsMovedDown() {
        val spec = CoverPaletteSpec(lightTextPrimaryTone = 70.0, lightTextSecondaryTone = 70.0)
        val c = palette(solid(red), 16, 16, dark = false, spec = spec)
        assertRails("bad light text", c, dark = false)
        assertTrue(tone(c.textPrimary) < 70.0, "moved down from 70, to ${tone(c.textPrimary)}")
    }

    @Test
    fun aBackgroundThatWouldBeTooBrightIsPulledUnderTheLuminanceCap() {
        val spec = CoverPaletteSpec(darkBackgroundTopTone = 70.0, darkSurfaceTone = 60.0, darkSurfaceVariantTone = 55.0)
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("bright backgrounds", c, dark = true)
        // The cap is a luminance of 0.12, tone 41: the colour comes down to it and stays a real, not black, colour.
        assertTrue(tone(c.backgroundTop) in 36.0..42.0, "top tone ${tone(c.backgroundTop)}")
        assertTrue(tone(c.surface) in 36.0..42.0, "surface tone ${tone(c.surface)}")
    }

    @Test
    fun theAccentToneIsCappedAt84() {
        val spec = CoverPaletteSpec(
            darkAccentTone = 97.0, darkAccentStrongTone = 95.0, darkButtonStartTone = 99.0, darkButtonEndTone = 92.0, darkGlowTone = 90.0,
        )
        val dark = palette(solid(yellow), 16, 16, dark = true, spec = spec)
        assertRails("bright accents", dark, dark = true)
        assertTrue(tone(dark.accent) in 80.0..84.5, "accent tone ${tone(dark.accent)}")
        assertTrue(tone(dark.buttonGradientStart) in 80.0..84.5, "button start tone ${tone(dark.buttonGradientStart)}")
        assertTrue(tone(dark.glow) in 80.0..84.5, "glow tone ${tone(dark.glow)}")
    }

    @Test
    fun anAccentThatIsTooCloseToTheSurfaceIsMovedAway() {
        // Tone 20 on a tone 10 surface has a contrast of about 1.3; the buttons are kept at tones that white text reads on.
        val spec = CoverPaletteSpec(
            darkAccentTone = 20.0, darkAccentStrongTone = 16.0, darkButtonStartTone = 44.0, darkButtonEndTone = 38.0,
        )
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("dull accent", c, dark = true)
        assertTrue(tone(c.accent) in 40.0..50.0, "the accent was lifted from 20 to about 44, not ${tone(c.accent)}")
    }

    @Test
    fun whenNoToneOfTheOnAccentColourIsReadableItFlipsToNearWhite() {
        // Accents of tone 42 to 46 are too light for black text (contrast 4.0 at best) and light enough for white.
        val spec = CoverPaletteSpec(
            darkAccentTone = 46.0, darkAccentStrongTone = 40.0, darkButtonStartTone = 46.0, darkButtonEndTone = 42.0,
        )
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("flipped on-accent", c, dark = true)
        assertTrue(tone(c.onAccent) >= 90.0, "the on-accent colour became near-white, tone ${tone(c.onAccent)}")
    }

    @Test
    fun whenNoToneIsReadableOnADarkAccentTheOnAccentFlipsToNearBlack() {
        // Light theme: white is the design's choice, but an accent of tone 62 to 70 is too pale for it.
        val spec = CoverPaletteSpec(
            lightAccentTone = 66.0, lightAccentStrongTone = 60.0, lightButtonStartTone = 70.0, lightButtonEndTone = 62.0,
        )
        val c = palette(solid(blueColor), 16, 16, dark = false, spec = spec)
        assertRails("flipped on-accent (light)", c, dark = false)
        assertTrue(tone(c.onAccent) <= 15.0, "the on-accent colour became near-black, tone ${tone(c.onAccent)}")
    }
}
