package com.cosmotune.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The layered surface scheme ([layered], [amoled]): every rail of the engine still holds over a sweep of seeds, both themes and
 * every intensity; the grounds are near neutral while the accent keeps the cover's colour; and the tiers climb in steps a card
 * can be told apart by.
 */
class LayeredSurfacesTest : ThemeTestBase() {

    private val intensities = listOf(0.7, 1.0, 1.25)
    private val seeds: List<Int> = (0 until 360 step 30).map { hue -> lchColour(0.62, 0.17, hue.toDouble()) } + listOf(apricot, red, blueColor, yellow)

    private fun layered(seed: Int, dark: Boolean, k: Double = 1.0, amoled: Boolean = false): CoverColors {
        val base = CoverPaletteSpec.forIntensity(k).layered()
        return palette(solid(seed), 16, 16, dark, if (amoled) base.amoled() else base)
    }

    @Test fun theDesignersDefaultsAreUntouched() {
        assertEquals(CoverPaletteSpec(), CoverPaletteSpec.forIntensity(1.0), "the golden spec is still the default")
        assertNotEquals(CoverPaletteSpec(), CoverPaletteSpec().layered(), "the layered scheme is a separate spec")
    }

    @Test fun everyRailHoldsForEverySeedThemeAndIntensity() {
        for (k in intensities) for (seed in seeds) for (dark in listOf(true, false)) {
            assertRails("layered ${hex(seed)} k=$k", layered(seed, dark, k), dark)
        }
        assertRails("layered grey", palette(greyRamp(), 32, 32, true, CoverPaletteSpec().layered()), dark = true)
    }

    @Test fun amoledHoldsTheRailsAndPutsThePageOnBlack() {
        for (seed in seeds) {
            val p = layered(seed, dark = true, amoled = true)
            assertRails("amoled ${hex(seed)}", p, dark = true)
            for (ground in listOf(p.backgroundTop, p.backgroundMid, p.backgroundBottom)) assertEquals(0, ground and 0xFFFFFF, "pure black: ${hex(ground)}")
            assertTrue(luminance(p.surface) > luminance(p.backgroundBottom), "a card still lifts off the black page")
        }
    }

    @Test fun theGroundsAreNearNeutralAndTheAccentKeepsTheCover() {
        for (seed in seeds.take(12)) for (dark in listOf(true, false)) {
            val p = layered(seed, dark)
            val designer = palette(solid(seed), 16, 16, dark)
            for ((name, ground) in listOf("backgroundTop" to p.backgroundTop, "backgroundMid" to p.backgroundMid, "surface" to p.surface, "surfaceHigh" to p.surfaceHigh)) {
                assertTrue(chromaOf(ground) <= 0.025, "${hex(seed)} $name chroma ${chromaOf(ground)} stays near neutral")
            }
            assertTrue(chromaOf(p.backgroundTop) <= chromaOf(designer.backgroundTop) + 0.004, "${hex(seed)}: the page carries no more colour than the designer's")
            assertTrue(chromaOf(p.accent) >= 0.05, "${hex(seed)}: the accent still carries the cover (${chromaOf(p.accent)})")
        }
    }

    @Test fun theDarkTiersClimbInVisibleSteps() {
        for (seed in seeds) {
            val p = layered(seed, dark = true)
            val steps = listOf(p.backgroundBottom, p.surface, p.surfaceHigh, p.surfaceVariant).map { luminance(it) }
            assertEquals(steps.sorted(), steps, "${hex(seed)}: page < card < sheet < chip")
            assertTrue(contrast(p.surfaceVariant, p.backgroundBottom) >= 1.3, "${hex(seed)}: a chip is apart from the page (${contrast(p.surfaceVariant, p.backgroundBottom)})")
            assertTrue(contrast(p.surface, p.backgroundBottom) >= 1.08, "${hex(seed)}: a card is apart from the page (${contrast(p.surface, p.backgroundBottom)})")
        }
    }

    @Test fun calmBalancedAndRichStillDiffer() {
        val seed = lchColour(0.62, 0.17, 20.0)
        val calm = layered(seed, dark = true, k = 0.7)
        val rich = layered(seed, dark = true, k = 1.25)
        assertTrue(Oklab.distance(calm.accent, rich.accent) > 0.005 || Oklab.distance(calm.ambient, rich.ambient) > 0.005, "the setting still moves the colour")
    }
}
