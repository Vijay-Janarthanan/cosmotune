package com.cosmotune.theme

import com.materialkolor.hct.Hct
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The designer's numbers and rails (`ui/v2/01-dynamic-colour.md`), asserted by hand: the lightness and chroma of every
 * token for a seed where no chroma cap binds, the accent and button rules, the bright, yellow and grey rules, the
 * rails over a sweep of hues, and the repair loops when the spec is retuned badly.
 */
class CoverPaletteRailsTest : ThemeTestBase() {

    /** A green seed of OKLCH chroma 0.06 (so no ceiling of the dark or light formulas binds). */
    private val greenSeed = lchColour(0.60, 0.06, 150.0)

    private fun assertToken(name: String, colour: Int, l: Double, c: Double, lTolerance: Double = 0.012, cTolerance: Double = 0.006) {
        val (actualL, actualC, _) = oklch(colour)
        assertAbout(l, actualL, lTolerance, "$name L")
        assertAbout(c, actualC, cTolerance, "$name chroma")
    }

    /** The reference's `inside`: a chroma held to 85 percent of the largest in-gamut chroma at that L and hue. */
    private fun inside(l: Double, c: Double, h: Double) = minOf(c, 0.85 * maxChroma(l, h))

    private fun assertHue(name: String, colour: Int, hue: Double, tolerance: Double) {
        assertTrue(hueDistance(hueOf(colour), hue) <= tolerance, "$name hue ${hueOf(colour)}, expected $hue plus or minus $tolerance")
    }

    // --- the numbers of the formulas ----------------------------------------------------------

    @Test
    fun theDarkThemeFollowsTheDesignersFormulas() {
        val seed = greenSeed
        val (_, cs, hs) = oklch(seed)
        assertAbout(0.06, cs, 0.004, "the seed's chroma")
        val c = cs // the chroma budget: min(Cs, 0.16), here Cs
        val p = solidPalette(seed, dark = true)
        assertRails("green seed (dark)", p, dark = true)
        assertToken("ambient", p.ambient, 0.30, minOf(0.9 * c, 0.09))
        assertToken("backgroundTop", p.backgroundTop, 0.28, minOf(0.55 * c, 0.055))
        assertToken("backgroundMid", p.backgroundMid, 0.225, minOf(0.42 * c, 0.040))
        assertToken("backgroundBottom", p.backgroundBottom, 0.185, minOf(0.30 * c, 0.028))
        assertToken("surface", p.surface, 0.26, minOf(0.35 * c, 0.035))
        assertToken("surfaceHigh", p.surfaceHigh, 0.30, minOf(0.32 * c, 0.032))
        assertToken("surfaceVariant", p.surfaceVariant, 0.34, minOf(0.30 * c, 0.032))
        assertToken("textPrimary", p.textPrimary, 0.965, minOf(0.08 * c, 0.010), lTolerance = 0.02, cTolerance = 0.004)
        assertToken("textSecondary", p.textSecondary, 0.80, minOf(0.14 * c, 0.022), lTolerance = 0.02, cTolerance = 0.004)
        assertToken("textTertiary", p.textTertiary, 0.71, minOf(0.12 * c, 0.020), lTolerance = 0.02, cTolerance = 0.004)
        // The accent: L is the seed's (0.60) held to 0.78..0.84; A = clamp(Cs, 0.07, 0.15) = 0.07.
        assertToken("accent", p.accent, 0.78, 0.07)
        assertHue("accent", p.accent, hs, 5.0)
        // accentStrong: (max(accent L, 0.84), min(accent C, 0.11)).
        assertToken("accentStrong", p.accentStrong, 0.84, 0.07, lTolerance = 0.02)
        // The button gradient: L 0.85 to 0.74; chroma clamp(0.85 A, 0.07, 0.13) and clamp(1.15 A, 0.09, 0.16); hue -14 then +14.
        assertToken("buttonGradientStart", p.buttonGradientStart, 0.85, inside(0.85, 0.07, hs - 14.0))
        assertToken("buttonGradientEnd", p.buttonGradientEnd, 0.74, inside(0.74, 0.09, hs + 14.0))
        assertHue("buttonGradientStart", p.buttonGradientStart, hs - 14.0, 6.0)
        assertHue("buttonGradientEnd", p.buttonGradientEnd, hs + 14.0, 6.0)
        // The container: (0.38, clamp(0.55 A, 0.04, 0.09)); its text (0.95, min(0.35 A, 0.04)).
        assertToken("accentContainer", p.accentContainer, 0.38, 0.04)
        assertToken("onAccentContainer", p.onAccentContainer, 0.95, minOf(0.35 * 0.07, 0.04), cTolerance = 0.005)
        // The glow: L is the seed's held to 0.42..0.58, chroma clamp(Cs, 0.06, 0.14).
        assertToken("glow", p.glow, 0.58, 0.06)
        assertToken("scrim", p.scrim, 0.12, minOf(0.25 * c, 0.025), lTolerance = 0.012, cTolerance = 0.004)
        assertToken("shadow", p.shadow, 0.20, minOf(0.6 * c, 0.08))
        assertEquals(140, p.shadow ushr 24, "the shadow is 55 percent opaque")
        assertEquals(56, p.track ushr 24, "the track is 22 percent opaque")
        assertEquals(36, p.outline ushr 24, "the outline is 14 percent opaque")
        // Track and outline are neutral (L 0.965, chroma 0).
        assertToken("track", p.track, 0.965, 0.0, cTolerance = 0.004)
        // The ground hues follow the seed.
        listOf(p.ambient, p.backgroundTop, p.backgroundMid, p.surface, p.surfaceHigh).forEach { assertHue("ground ${hex(it)}", it, hs, 12.0) }
        // onAccent: ink (0.18) or white; on these light buttons it is the ink.
        assertToken("onAccent", p.onAccent, 0.18, minOf(0.25 * c, 0.03), lTolerance = 0.015, cTolerance = 0.006)
    }

    @Test
    fun theLightThemeFollowsTheDesignersFormulas() {
        val seed = greenSeed
        val (_, cs, hs) = oklch(seed)
        val c = cs
        val p = solidPalette(seed, dark = false)
        assertRails("green seed (light)", p, dark = false)
        assertToken("ambient", p.ambient, 0.93, minOf(0.5 * c, 0.05))
        assertToken("backgroundTop", p.backgroundTop, 0.965, minOf(0.25 * c, 0.022))
        assertToken("backgroundMid", p.backgroundMid, 0.945, minOf(0.30 * c, 0.028))
        assertToken("backgroundBottom", p.backgroundBottom, 0.985, minOf(0.12 * c, 0.010))
        assertToken("surface", p.surface, 0.99, minOf(0.06 * c, 0.006), cTolerance = 0.004)
        assertToken("surfaceHigh", p.surfaceHigh, 0.975, minOf(0.10 * c, 0.010), cTolerance = 0.004)
        assertToken("surfaceVariant", p.surfaceVariant, 0.915, minOf(0.25 * c, 0.030))
        assertToken("textPrimary", p.textPrimary, 0.20, minOf(0.12 * c, 0.020), lTolerance = 0.02, cTolerance = 0.004)
        assertToken("textSecondary", p.textSecondary, 0.42, minOf(0.15 * c, 0.030), lTolerance = 0.02, cTolerance = 0.005)
        assertToken("textTertiary", p.textTertiary, 0.50, minOf(0.12 * c, 0.025), lTolerance = 0.02, cTolerance = 0.005)
        // The accent: L is the seed's (0.60) held to 0.46..0.54; A' = clamp(Cs, 0.09, 0.16) = 0.09.
        assertToken("accent", p.accent, 0.54, inside(0.54, 0.09, hs))
        assertToken("accentStrong", p.accentStrong, 0.50, inside(0.54, 0.09, hs), lTolerance = 0.02)
        // The button stops: chroma clamp(0.9 A', 0.08, 0.14) and clamp(1.1 A', 0.09, 0.16), held 15 percent inside the gamut edge.
        assertToken("buttonGradientStart", p.buttonGradientStart, 0.56, inside(0.56, 0.081, hs - 14.0), lTolerance = 0.02)
        assertToken("buttonGradientEnd", p.buttonGradientEnd, 0.46, inside(0.46, 0.099, hs + 14.0), lTolerance = 0.02)
        assertHue("buttonGradientStart", p.buttonGradientStart, hs - 14.0, 6.0)
        assertHue("buttonGradientEnd", p.buttonGradientEnd, hs + 14.0, 6.0)
        assertToken("accentContainer", p.accentContainer, 0.90, 0.04)
        assertToken("onAccentContainer", p.onAccentContainer, 0.28, minOf(0.5 * 0.09, 0.07), lTolerance = 0.02, cTolerance = 0.006)
        assertToken("glow", p.glow, 0.80, 0.06)
        assertEquals(white, p.onAccent, "white text on the light theme's buttons")
        assertEquals(56, p.shadow ushr 24, "the light shadow is 22 percent opaque")
        assertEquals(41, p.track ushr 24, "the light track is 16 percent opaque")
        assertEquals(31, p.outline ushr 24, "the light outline is 12 percent opaque")
    }

    @Test
    fun theCeilingsOfTheFormulasBindForAVividSeed() {
        // Pure red: chroma budget c = min(0.26, 0.16) = 0.16, so every background and surface sits on its ceiling.
        val p = solidPalette(red, dark = true)
        assertToken("backgroundTop", p.backgroundTop, 0.28, 0.055)
        assertToken("backgroundMid", p.backgroundMid, 0.225, 0.040)
        assertToken("backgroundBottom", p.backgroundBottom, 0.185, 0.028)
        assertToken("ambient", p.ambient, 0.30, 0.09, cTolerance = 0.008)
        assertToken("surface", p.surface, 0.26, 0.035)
        assertToken("surfaceVariant", p.surfaceVariant, 0.34, 0.032)
        // A vivid seed's accent chroma is held to 0.15, but never past 85 percent of what sRGB can show at that L and hue.
        val (l, c, h) = oklch(p.accent)
        assertTrue(c <= 0.15 + 0.006, "accent chroma $c")
        assertTrue(c <= 0.85 * maxChroma(l, h) + 0.006, "accent chroma $c is past 85 percent of the gamut edge ${maxChroma(l, h)}")
    }

    @Test
    fun theIntensitySettingScalesTheChromaBudget() {
        val seed = greenSeed
        val calm = palette(solid(seed), 16, 16, spec = CoverPaletteSpec(intensity = 0.7))
        val balanced = palette(solid(seed), 16, 16)
        val rich = palette(solid(seed), 16, 16, spec = CoverPaletteSpec(intensity = 1.25))
        listOf<(CoverColors) -> Int>({ it.backgroundTop }, { it.surface }, { it.ambient }).forEach { token ->
            assertTrue(chromaOf(token(calm)) < chromaOf(token(balanced)), "calm is calmer than balanced")
            assertTrue(chromaOf(token(balanced)) < chromaOf(token(rich)), "rich is richer than balanced")
        }
        // 0.7 x 0.06 = 0.042: the top stop's chroma is 0.55 of it.
        assertAbout(0.55 * 0.042, chromaOf(calm.backgroundTop), 0.005, "calm backgroundTop chroma")
        assertAbout(0.55 * 0.075, chromaOf(rich.backgroundTop), 0.006, "rich backgroundTop chroma")
        assertRails("calm", calm, dark = true)
        assertRails("rich", rich, dark = true)
    }

    // --- the bright, yellow and grey rules ---------------------------------------------------------------

    @Test
    fun aBrightCoverGetsCalmerGroundsAndAQuieterAccent() {
        // Same hue and chroma, lightness 0.90 (bright: above 0.86) against 0.70.
        val bright = solidPalette(lchColour(0.90, 0.05, 200.0), dark = true)
        val normal = solidPalette(lchColour(0.70, 0.05, 200.0), dark = true)
        // k = 0.7 on the background stops: 0.55 x 0.05 x 0.7 = 0.019 against 0.0275.
        assertAbout(0.7, chromaOf(bright.backgroundTop) / chromaOf(normal.backgroundTop), 0.1, "ground chroma ratio of a bright cover")
        // The accent is 0.02 darker (0.82 against 0.78) and its chroma is 0.87 of the normal one, which here is held at 0.07.
        assertAbout(0.82, lightness(bright.accent), 0.01, "bright accent L")
        assertAbout(0.78, lightness(normal.accent), 0.01, "normal accent L")
        // The surfaces are not touched by the bright factor.
        assertAbout(chromaOf(normal.surface), chromaOf(bright.surface), 0.003, "surface chroma")
    }

    @Test
    fun aYellowCoverTurnsItsGroundsTowardsAmberAndItsButtonsTheOtherWay() {
        val p = solidPalette(rgb(255, 229, 0), dark = true) // the designer's Sunblock
        val seedHue = hueOf(p.seed)
        assertAbout(101.0, seedHue, 1.5, "the OKLCH hue of #FFE500")
        // Grounds are rotated -12 degrees: amber, not olive.
        listOf(p.backgroundTop, p.backgroundMid, p.surface, p.surfaceHigh).forEach { assertHue("ground ${hex(it)}", it, seedHue - 12.0, 8.0) }
        // The bright rule gives a mustard accent: L 0.82 (0.84 - 0.02), the designer's #D6C65A.
        assertAbout(0.82, lightness(p.accent), 0.01, "yellow accent L")
        assertHue("accent", p.accent, seedHue, 5.0)
        // The buttons turn the other way for yellows: the light stop at +14, the dark one at -14.
        assertHue("buttonGradientStart", p.buttonGradientStart, seedHue + 14.0, 8.0)
        assertHue("buttonGradientEnd", p.buttonGradientEnd, seedHue - 14.0, 8.0)
        assertRails("pure yellow", p, dark = true)
    }

    @Test
    fun theButtonGradientRunsFromLightToDarkInBothThemes() {
        for (hue in 0..345 step 15) {
            val seed = lchColour(0.65, 0.14, hue.toDouble())
            val dark = solidPalette(seed, dark = true)
            val light = solidPalette(seed, dark = false)
            val name = "hue $hue"
            assertTrue(lightness(dark.buttonGradientStart) in 0.838..0.90, "$name dark start L ${lightness(dark.buttonGradientStart)}")
            assertTrue(lightness(dark.buttonGradientEnd) in 0.728..0.79, "$name dark end L ${lightness(dark.buttonGradientEnd)}")
            assertTrue(lightness(light.buttonGradientStart) in 0.50..0.575, "$name light start L ${lightness(light.buttonGradientStart)}")
            assertTrue(lightness(light.buttonGradientEnd) in 0.40..0.47, "$name light end L ${lightness(light.buttonGradientEnd)}")
            // Hue -14 then +14, reversed for 70 to 130 degrees.
            val seedHue = hueOf(seed)
            val sign = if (seedHue in 70.0..130.0) -1.0 else 1.0
            val (start, end) = dark.buttonGradientStart to dark.buttonGradientEnd
            if (chromaOf(start) > 0.04) assertTrue(hueDistance(hueOf(start), seedHue - 14.0 * sign) <= 8.0, "$name start hue ${hueOf(start)} (seed $seedHue)")
            if (chromaOf(end) > 0.04) assertTrue(hueDistance(hueOf(end), seedHue + 14.0 * sign) <= 8.0, "$name end hue ${hueOf(end)} (seed $seedHue)")
        }
    }

    @Test
    fun aGreyCoverGetsTheBrandPalette() {
        val dark = palette(greyRamp(), 32, 32, dark = true)
        val light = palette(greyRamp(), 32, 32, dark = false)
        assertTrue(dark.isFallback)
        assertEquals(apricot, dark.seed)
        assertEquals(apricot, dark.accent, "the brand apricot is the accent on dark")
        // On light the accent is (0.52, 0.10, 52): the designer's #96572E.
        assertToken("light accent", light.accent, 0.52, 0.10)
        assertHue("light accent", light.accent, 52.0, 3.0)
        // Warm neutral grounds (chroma budget 0.012), hue 52.
        assertToken("dark backgroundTop", dark.backgroundTop, 0.28, 0.55 * 0.012, cTolerance = 0.004)
        assertToken("glow", dark.glow, 0.58, 0.03, lTolerance = 0.02)
        assertRails("grey (dark)", dark, dark = true)
        assertRails("grey (light)", light, dark = false)
    }

    // --- the rails over a sweep ------------------------------------------------------------------------

    @Test
    fun theRailsHoldAcrossEveryHueAndChromaOfASyntheticCover() {
        var colourful = 0
        var total = 0
        for (hue in 0..345 step 15) {
            for (saturation in listOf(0.25, 0.6, 1.0)) { // low, mid and high chroma
                for (value in listOf(0.45, 0.9)) {
                    val argb = hsv(hue.toDouble(), saturation, value)
                    val name = "hue $hue saturation $saturation value $value (${hex(argb)})"
                    val (dark, light) = assertRailsBothThemes(name, solid(argb), 16, 16)
                    total++
                    if (!dark.isFallback) {
                        colourful++
                        // The seed is the cover's own colour: the pipeline did not wander off.
                        assertTrue(hueDistance(Hct.fromInt(dark.seed).hue, Hct.fromInt(argb).hue) <= 3.0, "$name: seed hue")
                        val seedHue = hueOf(dark.seed)
                        // The accent keeps the seed's hue: gamut is met by lowering chroma, not by clipping channels.
                        listOf("dark" to dark, "light" to light).forEach { (theme, p) ->
                            val (l, c, h) = oklch(p.accent)
                            if (c >= 0.04) assertTrue(hueDistance(h, seedHue) <= 5.0, "$name $theme accent hue $h, seed $seedHue")
                            // Chroma 0.07..0.15 (0.09..0.16 on light) as far as the gamut allows, and 15 percent inside the edge.
                            assertTrue(c <= (if (theme == "dark") 0.15 else 0.16) + 0.006, "$name $theme accent chroma $c")
                            assertTrue(c <= 0.85 * maxChroma(l, h) + 0.006, "$name $theme accent chroma $c is past 85 percent of the gamut edge ${maxChroma(l, h)}")
                        }
                        // Accent L: 0.78..0.84 on dark (0.76 for a bright cover), 0.46..0.54 on light.
                        assertTrue(lightness(dark.accent) in 0.75..0.85, "$name dark accent L ${lightness(dark.accent)}")
                        assertTrue(lightness(light.accent) in 0.45..0.55, "$name light accent L ${lightness(light.accent)}")
                        // Button text at least 4.5 on both ends (assertRails), and the gradient runs light to dark.
                        assertTrue(lightness(dark.buttonGradientStart) > lightness(dark.buttonGradientEnd) + 0.05, "$name dark gradient direction")
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

    // --- the repair loops, when the spec is retuned badly ----------------------------------------------

    @Test
    fun theSpecNumbersReachThePalette() {
        val spec = CoverPaletteSpec(darkBackgroundTop = ChromaRule(0.25, 0.55, 0.055), darkAccentMinL = 0.70, darkAccentMaxL = 0.70)
        val c = palette(solid(greenSeed), 16, 16, dark = true, spec = spec)
        assertAbout(0.25, lightness(c.backgroundTop), 0.012, "backgroundTop L")
        assertAbout(0.70, lightness(c.accent), 0.012, "accent L")
        assertRails("retuned", c, dark = true)
    }

    @Test
    fun aTextThatIsTooDarkOnADarkThemeIsMovedUpUntilItIsReadable() {
        val spec = CoverPaletteSpec(darkTextPrimary = ChromaRule(0.45, 0.08, 0.010), darkTextSecondary = ChromaRule(0.45, 0.14, 0.022))
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("bad text L", c, dark = true)
        assertTrue(lightness(c.textPrimary) > 0.55, "moved up from 0.45, to ${lightness(c.textPrimary)}")
        assertTrue(lightness(c.textSecondary) > 0.55, "moved up from 0.45, to ${lightness(c.textSecondary)}")
    }

    @Test
    fun aLightTextThatIsTooPaleIsMovedDown() {
        val spec = CoverPaletteSpec(lightTextPrimary = ChromaRule(0.75, 0.12, 0.02), lightTextSecondary = ChromaRule(0.75, 0.15, 0.03))
        val c = palette(solid(red), 16, 16, dark = false, spec = spec)
        assertRails("bad light text", c, dark = false)
        assertTrue(lightness(c.textPrimary) < 0.60, "moved down from 0.75, to ${lightness(c.textPrimary)}")
    }

    @Test
    fun aBackgroundThatWouldBeTooBrightIsPulledUnderTheDarknessRail() {
        // A top stop asked for at L 0.50 (relative luminance 0.125): lowered 0.005 at a time to luminance 0.030 and L 0.30.
        val spec = CoverPaletteSpec(
            darkBackgroundTop = ChromaRule(0.50, 0.55, 0.055), darkAmbient = ChromaRule(0.45, 0.9, 0.09),
            darkBackgroundMid = ChromaRule(0.40, 0.42, 0.04),
        )
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertRails("bright backgrounds", c, dark = true)
        assertTrue(lightness(c.backgroundTop) in 0.27..0.30, "top L ${lightness(c.backgroundTop)}")
        assertTrue(lightness(c.ambient) in 0.27..0.30, "ambient L ${lightness(c.ambient)}")
        assertTrue(luminance(c.backgroundTop) <= 0.030, "top luminance ${luminance(c.backgroundTop)}")
    }

    @Test
    fun aLightBackgroundThatWouldBeTooDarkIsRaisedToTheFloor() {
        val spec = CoverPaletteSpec(lightBackgroundMid = ChromaRule(0.80, 0.30, 0.028), lightAmbient = ChromaRule(0.70, 0.5, 0.05))
        val c = palette(solid(red), 16, 16, dark = false, spec = spec)
        assertRails("dark light-backgrounds", c, dark = false)
        assertTrue(lightness(c.backgroundMid) >= 0.93 - 1e-6, "mid L ${lightness(c.backgroundMid)}")
        assertTrue(lightness(c.ambient) >= 0.93 - 1e-6, "ambient L ${lightness(c.ambient)}")
    }

    @Test
    fun anAccentThatIsTooCloseToTheSurfacesIsMovedAway() {
        val spec = CoverPaletteSpec(darkAccentMinL = 0.30, darkAccentMaxL = 0.30)
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        // The progress rail (accent on its track) is logged by the reference, not repaired, so a retune can break it.
        assertRails("dull accent", c, dark = true, checkProgress = false)
        assertTrue(lightness(c.accent) > 0.40, "the accent was lifted from 0.30, to ${lightness(c.accent)}")
    }

    @Test
    fun whenNeitherInkNorWhiteReadsOnTheButtonsTheStopsMoveAwayFromTheBetterOne() {
        // Both stops at L 0.58: ink 4.4:1, white 4.3:1. The ink wins and both stops move 0.01 lighter until it passes.
        val spec = CoverPaletteSpec(
            darkButtonStart = AccentChromaRule(0.58, 0.85, 0.07, 0.13), darkButtonEnd = AccentChromaRule(0.58, 1.15, 0.09, 0.16),
        )
        val c = palette(solid(greenSeed), 16, 16, dark = true, spec = spec)
        assertRails("stops moved", c, dark = true)
        assertTrue(lightness(c.buttonGradientStart) > 0.58, "the start moved lighter: ${lightness(c.buttonGradientStart)}")
        assertTrue(lightness(c.buttonGradientEnd) > 0.58, "the end moved lighter: ${lightness(c.buttonGradientEnd)}")
        assertTrue(lightness(c.onAccent) < 0.30, "the text on them is the ink: ${lightness(c.onAccent)}")
    }

    @Test
    fun whenNothingReadsTheForegroundBecomesWhiteOrInk() {
        // Impossible numbers: text tokens at the same L as the grounds, and a contrast loop that may not move them.
        val spec = CoverPaletteSpec(darkTextPrimary = ChromaRule(0.30, 0.08, 0.010), contrastMaxSteps = 0)
        val c = palette(solid(blueColor), 16, 16, dark = true, spec = spec)
        assertEquals(white, c.textPrimary, "with no steps allowed the text falls back to white")
        // The ink fallback exists for the light theme.
        val light = palette(solid(blueColor), 16, 16, dark = false, spec = CoverPaletteSpec(lightTextPrimary = ChromaRule(0.97, 0.12, 0.02), contrastMaxSteps = 0))
        assertTrue(lightness(light.textPrimary) < 0.25, "the ink is dark: ${lightness(light.textPrimary)}")
    }

    @Test
    fun theDesignersLightnessLimitsAreFieldsOfTheSpec() {
        // The numbers the safety rails use are spec fields, so a retune can reach them.
        val spec = CoverPaletteSpec()
        assertEquals(0.30, spec.darkGroundMaxL)
        assertEquals(0.030, spec.darkGroundMaxLuminance)
        assertEquals(0.93, spec.lightGroundMinL)
        assertEquals(4.5, spec.textContrast)
        assertEquals(3.0, spec.uiContrast)
        assertEquals(700, spec.fadeMillis)
        assertEquals(150, spec.fadeMillisReducedMotion)
        assertEquals(0.10, spec.settleSameAlbumDeltaE)
        assertEquals(0.03, spec.settleAcrossAlbumDeltaE)
    }
}
