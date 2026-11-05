package com.cosmotune.theme

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Blending two palettes ([CoverPalette.lerp] and the fade's easing) and keeping the one on screen ([CoverPalette.settle]). */
class CoverPaletteBlendTest : ThemeTestBase() {

    private fun distance(a: Int, b: Int): Double {
        val x = oklab(a)
        val y = oklab(b)
        return hypot(hypot(x[0] - y[0], x[1] - y[1]), x[2] - y[2])
    }

    // --- lerp --------------------------------------------------------------------------------------

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
        val before = opaqueFields(a)
        val after = opaqueFields(b)
        val middle = opaqueFields(mid)
        for (i in before.indices) {
            val (field, from) = before[i]
            val to = after[i].second
            val lo = min(oklabL(from), oklabL(to))
            val hi = max(oklabL(from), oklabL(to))
            val l = oklabL(middle[i].second)
            // Eight-bit rounding can move L by about 0.002.
            assertTrue(l >= lo - 0.005 && l <= hi + 0.005, "$field: L $l is not between $lo and $hi")
            // OKLab is where the straight line is drawn, so the lightness is the mean (not a mix done on the channels).
            assertTrue(abs(l - (oklabL(from) + oklabL(to)) / 2.0) <= 0.015, "$field: L $l is not the mean")
            assertEquals(0xFF, middle[i].second ushr 24, "$field: an interpolated opaque colour is opaque")
        }
        assertNotEquals(a, mid)
        assertNotEquals(b, mid)
    }

    @Test
    fun theAlphaOfTheTranslucentTokensBlendsLinearly() {
        val dark = solidPalette(blueColor, dark = true)
        val light = solidPalette(blueColor, dark = false)
        val mid = CoverPalette.lerp(dark, light, 0.5f)
        assertEquals(140, dark.shadow ushr 24)
        assertEquals(56, light.shadow ushr 24)
        assertTrue(abs((mid.shadow ushr 24) - 98) <= 1, "shadow alpha ${mid.shadow ushr 24}")
        assertTrue(abs((mid.track ushr 24) - (56 + 41) / 2) <= 1, "track alpha ${mid.track ushr 24}")
        assertTrue(abs((mid.outline ushr 24) - (36 + 31) / 2) <= 1, "outline alpha ${mid.outline ushr 24}")
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
    fun lerpKeepsEveryColourAColourWhenTheEndsAreFarApart() {
        val a = solidPalette(red)
        val b = solidPalette(blueColor)
        for (step in 0..20) {
            val c = CoverPalette.lerp(a, b, step / 20f)
            opaqueFields(c).forEach { (field, colour) -> assertEquals(0xFF, colour ushr 24, "$field at step $step") }
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

    @Test
    fun noFrameOfAFadeLosesTheTextContrast() {
        // The designer's "no flash" rule: text and grounds are blended at the same t, every dark ground is dark and every text
        // token light, so the worst contrast at any frame stays high (the designer measured 6.18:1 over its twelve palettes).
        val seeds = listOf(
            red, blueColor, yellow, white, rgb(255, 45, 149), rgb(0, 229, 212), rgb(70, 96, 68), rgb(241, 173, 117),
        )
        val palettes = seeds.map { solidPalette(it, dark = true) }
        var worst = 99.0
        for (a in palettes) for (b in palettes) {
            if (a === b) continue
            for (i in 0..20) {
                val t = CoverPalette.fadeProgress(i / 20f)
                val m = CoverPalette.lerp(a, b, t)
                listOf(
                    m.textPrimary to m.surfaceVariant, m.textSecondary to m.surfaceVariant, m.textPrimary to m.backgroundTop,
                    m.onAccent to m.buttonGradientStart, m.onAccent to m.buttonGradientEnd,
                ).forEach { (fg, bg) -> worst = min(worst, contrast(fg, bg)) }
            }
        }
        assertTrue(worst >= 4.5, "the worst text contrast at any frame of any fade is $worst")
        println("worst text contrast over ${palettes.size * (palettes.size - 1)} fades: $worst")
    }

    // --- the fade's easing -------------------------------------------------------------------------

    @Test
    fun theFadeStartsAtZeroEndsAtOneAndNeverGoesBack() {
        assertEquals(0f, CoverPalette.fadeProgress(0f), 1e-6f)
        assertEquals(1f, CoverPalette.fadeProgress(1f), 1e-6f)
        var previous = 0f
        for (i in 1..100) {
            val y = CoverPalette.fadeProgress(i / 100f)
            assertTrue(y >= previous - 1e-6f, "progress went back at $i: $y after $previous")
            previous = y
        }
        // cubic-bezier(0.2, 0, 0, 1) is quick to leave and slow to arrive: more than half done at 20 percent, nearly done at 80.
        assertTrue(CoverPalette.fadeProgress(0.2f) >= 0.45f)
        assertTrue(CoverPalette.fadeProgress(0.8f) >= 0.97f)
        // Out-of-range input is held.
        assertEquals(0f, CoverPalette.fadeProgress(-1f), 1e-6f)
        assertEquals(1f, CoverPalette.fadeProgress(2f), 1e-6f)
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
        assertSame(previous, CoverPalette.settle(previous, next, sameAlbum = true))
    }

    @Test
    fun settleTakesTheNextPaletteForADifferentCover() {
        val previous = solidPalette(red)
        val next = solidPalette(blueColor)
        assertSame(next, CoverPalette.settle(previous, next))
        assertSame(next, CoverPalette.settle(previous, next, sameAlbum = true))
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
    fun settleHonoursAnExplicitThreshold() {
        val previous = solidPalette(red)
        val next = solidPalette(blueColor)
        assertSame(next, CoverPalette.settle(previous, next, 0f), "a threshold of zero never keeps the previous")
        assertSame(previous, CoverPalette.settle(previous, next, 1000f), "a huge threshold always keeps it")
        val slightly = palette(solid(rgb(255, 10, 0)), 16, 16)
        assertSame(previous, CoverPalette.settle(previous, slightly, 0.03f))
        assertSame(slightly, CoverPalette.settle(previous, slightly, 0f), "even an identical-looking palette is replaced at a threshold of zero")
    }

    @Test
    fun theSameAlbumToleratesMoreThanAnotherAlbum() {
        // Two greens of the same lightness whose chroma differs, so that the accents are 0.03 to 0.10 apart in OKLab.
        val previous = solidPalette(lchColour(0.60, 0.06, 150.0))
        val next = solidPalette(lchColour(0.60, 0.12, 150.0))
        val gap = max(distance(previous.accent, next.accent), distance(previous.backgroundTop, next.backgroundTop))
        assertTrue(gap in 0.035..0.095, "the two palettes are $gap apart: the test needs a gap between the two thresholds")
        assertSame(next, CoverPalette.settle(previous, next), "another album: re-theme from 0.03")
        assertSame(next, CoverPalette.settle(previous, next, sameAlbum = false))
        assertSame(previous, CoverPalette.settle(previous, next, sameAlbum = true), "the same album: keep until 0.10")
    }

    @Test
    fun settleReadsItsThresholdsFromTheSpecThatWasPassed() {
        val previous = solidPalette(red)
        val next = solidPalette(blueColor)
        assertSame(previous, CoverPalette.settle(previous, next, CoverPaletteSpec(settleAcrossAlbumDeltaE = 1000.0)))
        assertSame(next, CoverPalette.settle(previous, next, CoverPaletteSpec(settleAcrossAlbumDeltaE = 1000.0, settleSameAlbumDeltaE = 0.0), sameAlbum = true))
        assertSame(previous, CoverPalette.settle(previous, next, CoverPaletteSpec(settleSameAlbumDeltaE = 1000.0), sameAlbum = true))
    }

    @Test
    fun settleIgnoresASeedThatOnlyDiffersInLightness() {
        // Two covers of the same hue and chroma but different lightness: the palette is derived from hue and chroma (and
        // the accent's L is the seed's held to 0.78..0.84), so the screen looks the same and nothing may be animated.
        val darker = palette(solid(lchColour(0.35, 0.10, 270.0)), 16, 16)
        val lighter = palette(solid(lchColour(0.60, 0.10, 270.0)), 16, 16)
        assertNotEquals(darker.seed, lighter.seed)
        assertTrue(abs(oklabL(darker.seed) - oklabL(lighter.seed)) > 0.2, "the seeds themselves are far apart in lightness")
        assertTrue(distance(darker.accent, lighter.accent) < 0.01, "the accents look the same")
        assertSame(darker, CoverPalette.settle(darker, lighter))
    }

    @Test
    fun settleTakesTheNextPaletteWhenTheAccentChangesEvenIfTheBackgroundHardlyDoes() {
        // The same lightness and chroma, a different hue: the accent (L 0.78) is the most visible colour and it moved.
        val blue = solidPalette(lchColour(0.60, 0.15, 263.0))
        val green = solidPalette(lchColour(0.60, 0.15, 150.0))
        assertSame(green, CoverPalette.settle(blue, green))
    }

    @Test
    fun settleNeverKeepsAFallbackPaletteForARealOneOrTheOtherWayRound() {
        // A white cover (the brand palette) and a real cover. Even with a threshold so large that any two palettes "look the
        // same", a real colour never hides behind the fallback, nor the fallback behind a real colour.
        val fallback = solidPalette(white)
        val real = solidPalette(apricot)
        assertTrue(fallback.isFallback)
        assertFalse(real.isFallback)
        assertSame(real, CoverPalette.settle(fallback, real, 1000f))
        assertSame(fallback, CoverPalette.settle(real, fallback, 1000f))
        assertSame(real, CoverPalette.settle(fallback, real, CoverPaletteSpec(settleAcrossAlbumDeltaE = 1000.0)))
        // Two fallbacks are the same palette and are kept.
        val another = solidPalette(black)
        assertSame(fallback, CoverPalette.settle(fallback, another))
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
}
