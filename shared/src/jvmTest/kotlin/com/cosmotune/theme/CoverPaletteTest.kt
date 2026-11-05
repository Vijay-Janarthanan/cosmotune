package com.cosmotune.theme

import com.materialkolor.hct.Hct
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading a cover: sampling, the designer's pre-filter, the seed (the quantiser and the scorer, or the exact histogram of
 * a flat cover), the grey-cover fallback and determinism. The expectations are written by hand; the colour maths of the
 * tests (contrast, OKLCH) is the tests' own, in [ThemeTestBase], and does not use the code under test.
 */
class CoverPaletteTest : ThemeTestBase() {

    // --- single covers -----------------------------------------------------------------------------

    @Test
    fun solidRedKeepsItsHue() {
        val (dark, light) = assertRailsBothThemes("solid red", solid(red), 16, 16)
        assertFalse(dark.isFallback)
        assertFalse(light.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(seedHue in 20.0..35.0, "the seed hue of pure red is about 27 in HCT, was $seedHue")
        val seedOklchHue = hueOf(dark.seed)
        assertAbout(29.0, seedOklchHue, 3.0, "the OKLCH hue of pure red")
        assertTrue(hueDistance(hueOf(dark.accent), seedOklchHue) <= 4.0, "the accent keeps the seed's hue: ${hueOf(dark.accent)}")
        assertTrue(hueDistance(hueOf(light.accent), seedOklchHue) <= 4.0, "the light accent keeps the seed's hue: ${hueOf(light.accent)}")
    }

    @Test
    fun solidBlueKeepsItsHue() {
        val (dark, light) = assertRailsBothThemes("solid blue", solid(blueColor), 16, 16)
        assertFalse(dark.isFallback)
        val seedHue = Hct.fromInt(dark.seed).hue
        assertTrue(seedHue in 275.0..290.0, "the seed hue of pure blue is about 283 in HCT, was $seedHue")
        val seedOklchHue = hueOf(dark.seed)
        assertAbout(264.0, seedOklchHue, 3.0, "the OKLCH hue of pure blue")
        assertTrue(hueDistance(hueOf(dark.accent), seedOklchHue) <= 4.0, "the accent keeps the seed's hue: ${hueOf(dark.accent)}")
        assertTrue(hueDistance(hueOf(light.accent), seedOklchHue) <= 4.0, "the light accent keeps the seed's hue: ${hueOf(light.accent)}")
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
    fun aYellowCoverKeepsItsHueButStaysCalm() {
        // The designer's Sunblock: #FFE500 (OKLab L 0.915, under the near-white cut of 0.95).
        val sunblock = rgb(255, 229, 0)
        val (dark, light) = assertRailsBothThemes("yellow", solid(sunblock), 16, 16)
        assertFalse(dark.isFallback)
        val seedOklchHue = hueOf(dark.seed)
        assertAbout(101.0, seedOklchHue, 3.0, "the OKLCH hue of #FFE500")
        // The accent is a calm mustard of the same hue (the designer's #D6C65A); the grounds are dark amber, never bright.
        assertTrue(hueDistance(hueOf(dark.accent), seedOklchHue) <= 6.0, "the accent is still yellow: ${hueOf(dark.accent)}")
        assertTrue(lightness(dark.accent) <= 0.84 + 0.005, "the accent is held at L 0.84 or under: ${lightness(dark.accent)}")
        assertTrue(hueDistance(hueOf(light.accent), seedOklchHue) <= 8.0, "the light accent is still yellow: ${hueOf(light.accent)}")
        listOf(dark.backgroundTop, dark.backgroundMid, dark.backgroundBottom, dark.ambient).forEach {
            assertTrue(luminance(it) <= 0.030, "a yellow cover must not make a bright background")
        }
    }

    @Test
    fun pureYellowFFFF00IsNearWhiteForTheDesignersPreFilter() {
        // #FFFF00 has an OKLab L of 0.968, above the 0.95 cut, so the designer's pre-filter drops every pixel of it: a cover of
        // exactly that yellow is a grey cover and gets the brand palette. (A real yellow cover such as #FFE500 is not affected.)
        assertTrue(lightness(yellow) > 0.95)
        val c = solidPalette(yellow)
        assertTrue(c.isFallback)
        assertEquals(apricot, c.seed)
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
        // A blue fallback seed is not the brand apricot: the progress rail is the reference's to log, not to repair.
        assertRails("custom fallback", c, dark = true, checkProgress = false)
    }

    @Test
    fun aFallbackSeedWithTransparencyIsMadeOpaque() {
        val c = CoverPalette.fromPixels(solid(white), 16, 16, fallbackSeed = 0x00336699)
        assertEquals(0xFF336699.toInt(), c.seed)
    }

    @Test
    fun theFallbackPaletteIsTheBrandPalette() {
        // The designer's grey rule: a warm neutral ground (chroma 0.012) and the brand apricot as the accent on dark.
        val c = solidPalette(white, dark = true)
        assertTrue(c.isFallback)
        assertEquals(apricot, c.accent)
        listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom).forEach {
            assertTrue(luminance(it) <= 0.030, "calm ground ${hex(it)}")
            assertTrue(chromaOf(it) < 0.02, "a warm neutral, not a colour: chroma ${chromaOf(it)}")
        }
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


    // --- covers that go through the quantiser (more than 128 distinct colours) -----------------------


    @Test
    fun aBlueGradientWithNoiseAndASmallOrangeLogoSeedsFromTheBlue() {
        val pixels = noisyBlueGradientWithOrangeLogo()
        assertTrue(distinctBins(pixels, 64, 64) > 128, "the test cover must go through the quantiser, it has ${distinctBins(pixels, 64, 64)} colours")
        val (dark, light) = assertRailsBothThemes("noisy blue gradient, orange logo", pixels, 64, 64)
        val hue = Hct.fromInt(dark.seed).hue
        assertFalse(dark.isFallback)
        // The blue of the gradient has an HCT hue of about 263; the orange logo sits near 56.
        assertTrue(hueDistance(hue, 263.0) <= 15.0, "the seed hue should be the blue, about 263, was $hue (seed #${dark.seed.toUInt().toString(16)})")
        assertTrue(hueDistance(hue, 56.0) > 90.0, "the seed must not be the orange logo")
        assertEquals(dark.seed, light.seed, "the seed does not depend on the theme")
    }

    @Test
    fun aCoverThroughTheQuantiserGivesTheSamePaletteEveryTime() {
        val pixels = noisyBlueGradientWithOrangeLogo()
        assertTrue(distinctBins(pixels, 64, 64) > 128)
        val first = palette(pixels, 64, 64)
        palette(photoLike(rgb(20, 140, 150), rgb(210, 40, 50), 0.3, 7), 64, 64) // another cover in between
        val second = palette(pixels, 64, 64)
        assertEquals(first, second)
        assertEquals(palette(pixels, 64, 64, dark = false), palette(pixels.copyOf(), 64, 64, dark = false))
    }

    @Test
    fun coversJustBelowAndJustAboveTheBoundaryAgreeOnTheSeedHue() {
        // 128 colours are read from the histogram, 129 go through the quantiser: the switch must not change the colour of the app.
        val below = coverWithBins(128)
        val above = coverWithBins(129)
        assertEquals(128, distinctBins(below, 64, 64))
        assertEquals(129, distinctBins(above, 64, 64))
        val seedBelow = palette(below, 64, 64).seed
        val seedAbove = palette(above, 64, 64).seed
        val hueBelow = Hct.fromInt(seedBelow).hue
        val hueAbove = Hct.fromInt(seedAbove).hue
        assertTrue(hueDistance(hueBelow, hueAbove) <= 10.0, "hue below the boundary $hueBelow, above $hueAbove")
        // Both are the blue family of the lattice (HCT hue 255 to 285).
        assertTrue(hueDistance(hueBelow, 270.0) <= 15.0, "below: $hueBelow")
        assertTrue(hueDistance(hueAbove, 270.0) <= 15.0, "above: $hueAbove")
        assertFalse(palette(below, 64, 64).isFallback)
        assertFalse(palette(above, 64, 64).isFallback)
    }

    @Test
    fun aPhotoLikeCoverSeedsFromTheRegionThatCoversMostOfIt() {
        // A teal field with a red object over 30 percent of the picture. Score ranks a colour by the share of the
        // picture in its hue neighbourhood (0.7 points per percent) plus a chroma bonus (0.3 points per chroma unit
        // above 48). The teal's 70 percent is worth about 49 points; the red's 30 percent is worth about 21, and
        // even a chroma of 100 (+16) cannot make up the 28-point gap. So the larger region wins, as it should: the
        // field is what the eye reads as "the colour of the cover", the vivid object is an accent.
        val teal = rgb(20, 140, 150)
        val red = rgb(210, 40, 50)
        val pixels = photoLike(field = teal, obj = red, objectShare = 0.3, seed = 11)
        assertTrue(distinctBins(pixels, 64, 64) > 128, "it must go through the quantiser: ${distinctBins(pixels, 64, 64)} colours")
        val objectShare = pixels.count { red(it) > green(it) + 60 }.toDouble() / pixels.size
        assertTrue(objectShare in 0.25..0.35, "the red object covers $objectShare of the cover")
        val (dark, _) = assertRailsBothThemes("teal with a red object", pixels, 64, 64)
        val hue = Hct.fromInt(dark.seed).hue
        val tealHue = Hct.fromInt(teal).hue
        val redHue = Hct.fromInt(red).hue
        assertTrue(hueDistance(hue, tealHue) <= 25.0, "the seed should be the teal field (hue $tealHue), was $hue")
        assertTrue(hueDistance(hue, redHue) > 90.0, "the seed must not be the red object (hue $redHue)")
    }

    @Test
    fun theSameCoverWithTheRedObjectOverMostOfItSeedsFromTheRed() {
        // The mirror of the test above: 60 percent red, 40 percent teal. The rule is "the larger region", not "a hue".
        val teal = rgb(20, 140, 150)
        val red = rgb(210, 40, 50)
        val pixels = photoLike(field = teal, obj = red, objectShare = 0.6, seed = 12)
        assertTrue(distinctBins(pixels, 64, 64) > 128)
        val (dark, _) = assertRailsBothThemes("red with a teal border", pixels, 64, 64)
        val hue = Hct.fromInt(dark.seed).hue
        assertTrue(hueDistance(hue, Hct.fromInt(red).hue) <= 25.0, "the seed should be the red, was $hue")
    }

    // --- the designer's pre-filter and the second colour -----------------------------------------------

    private val orange = rgb(255, 140, 30)
    private val deepBlue = rgb(30, 80, 200)

    /** A 40 x 40 cover (1600 pixels): the first [logoPixels] pixels are [logo], the rest [ground]. */
    private fun withLogo(ground: Int, logo: Int, logoPixels: Int): IntArray = IntArray(1600) { i -> if (i < logoPixels) logo else ground }

    @Test
    fun aMostlyBlackCoverTakesItsSeedFromTheOrangeNotFromTheBlack() {
        // Without the pre-filter the quantiser picks the dominant near-black; the designer drops near-black pixels first.
        val c = palette(withLogo(rgb(6, 7, 9), orange, 160), 40, 40)
        assertFalse(c.isFallback)
        assertAbout(oklch(orange).third, hueOf(c.seed), 8.0, "the seed is the orange")
    }

    @Test
    fun aMostlyWhiteCoverTakesItsSeedFromTheColourOnIt() {
        val c = palette(withLogo(rgb(250, 250, 250), deepBlue, 130), 40, 40)
        assertFalse(c.isFallback)
        assertAbout(oklch(deepBlue).third, hueOf(c.seed), 8.0, "the seed is the blue")
        // A cover that is mostly white is bright: the grounds are calmer (the bright factor) but the palette is the usual kind.
        assertRailsBothThemes("white with a blue mark", withLogo(rgb(250, 250, 250), deepBlue, 130), 40, 40)
    }

    @Test
    fun underOnePointFivePercentOfColourMeansAGreyCover() {
        // 1600 pixels: 23 are 1.44 percent, 25 are 1.56 percent. The share is counted after the pre-filter, of all pixels.
        val under = palette(withLogo(black, orange, 23), 40, 40)
        val over = palette(withLogo(black, orange, 25), 40, 40)
        assertTrue(under.isFallback, "1.44 percent colour is a grey cover")
        assertFalse(over.isFallback, "1.56 percent colour is a cover with a colour")
        assertEquals(apricot, under.seed)
        assertAbout(oklch(orange).third, hueOf(over.seed), 8.0, "the seed of the cover with a little orange")
    }

    @Test
    fun nearGreyPixelsAreNotColour() {
        // A warm grey (chroma 0.01, below the 0.03 cut) over the whole cover and a 1 percent red mark: no colour.
        val grey = rgb(128, 122, 120)
        assertTrue(chromaOf(grey) < 0.03)
        assertTrue(palette(withLogo(grey, red, 16), 40, 40).isFallback)
        // The same grey made one level more colourful than the cut is a colour: the seed is that grey-brown.
        val tinted = rgb(150, 110, 100)
        assertTrue(chromaOf(tinted) > 0.03)
        assertFalse(palette(solid(tinted), 16, 16).isFallback)
    }

    @Test
    fun theSecondColourOfTheCoverColoursTheBottomStop() {
        // 70 percent blue and 30 percent orange: the seed is the blue, the secondary the orange, 200 degrees away.
        val pixels = IntArray(1600) { i -> if (i < 480) orange else deepBlue }
        val dark = palette(pixels, 40, 40)
        assertAbout(oklch(deepBlue).third, hueOf(dark.seed), 6.0, "the seed")
        assertTrue(hueDistance(hueOf(dark.backgroundTop), oklch(deepBlue).third) <= 15.0, "the top stop is blue: ${hueOf(dark.backgroundTop)}")
        assertTrue(hueDistance(hueOf(dark.backgroundBottom), oklch(orange).third) <= 15.0, "the bottom stop is orange: ${hueOf(dark.backgroundBottom)}")
        val light = palette(pixels, 40, 40, dark = false)
        assertTrue(hueDistance(hueOf(light.backgroundBottom), hueOf(light.backgroundTop)) > 60.0, "the light bottom stop is the second colour too")
    }

    @Test
    fun aSecondColourWithinTwentyFiveDegreesIsIgnored() {
        val near = rgb(90, 60, 220)
        val gap = hueDistance(oklch(deepBlue).third, oklch(near).third)
        assertTrue(gap in 15.0..25.0, "the two blues are $gap degrees apart")
        val pixels = IntArray(1600) { i -> if (i < 480) near else deepBlue }
        val c = palette(pixels, 40, 40)
        assertTrue(hueDistance(hueOf(c.backgroundBottom), hueOf(c.backgroundTop)) <= 8.0, "the bottom stop keeps the seed's hue")
    }
}
