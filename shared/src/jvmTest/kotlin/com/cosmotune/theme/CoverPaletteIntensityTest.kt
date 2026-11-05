package com.cosmotune.theme

import com.cosmotune.design.Intensity
import com.cosmotune.design.LookSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Intensity setting (Settings > Appearance: Calm 0.7, Balanced 1.0, Rich 1.25) through [CoverPaletteSpec.forIntensity]: the chroma
 * budget, the chroma ceilings and the share of the gamut the accent may take all move with it, so the three settings look different;
 * Balanced is the designer's spec, unchanged (the golden vectors hold for it byte for byte); and every rail holds at every setting.
 *
 * The golden-style part: ten covers (the six of [LookSamples] and four more, striped the same way), both themes, the three settings.
 */
class CoverPaletteIntensityTest : ThemeTestBase() {

    private class Cover(val name: String, val a: Int, val b: Int) {
        fun pixels(): IntArray = IntArray(64 * 64) { i -> if ((i % 64) / 8 % 2 == 0) a else b }
    }

    private val covers: List<Cover> = LookSamples.all.map { Cover(it.name, it.a, it.b) } + listOf(
        Cover("Teal", 0xFF00E5D4.toInt(), 0xFF0B3B38.toInt()),
        Cover("Violet", 0xFF7B3FC4.toInt(), 0xFF22113A.toInt()),
        Cover("Vivid green", 0xFF1DB954.toInt(), 0xFF0E2A17.toInt()),
        Cover("Dusty pink", 0xFFC48A9A.toInt(), 0xFF3A2328.toInt()),
    )

    private val settings = listOf(Intensity.CALM, Intensity.BALANCED, Intensity.RICH).map { Intensity.multiplier(it) }

    private fun paletteOf(cover: Cover, dark: Boolean, intensity: Double) =
        CoverPalette.fromPixels(cover.pixels(), 64, 64, dark, CoverPaletteSpec.forIntensity(intensity))

    @Test fun balancedIsTheDesignersSpecExactly() {
        assertEquals(CoverPaletteSpec(), CoverPaletteSpec.forIntensity(1.0))
        for (cover in covers) for (dark in listOf(true, false)) {
            assertEquals(
                CoverPalette.fromPixels(cover.pixels(), 64, 64, dark),
                paletteOf(cover, dark, 1.0),
                "${cover.name}: Balanced is the palette of the default spec",
            )
        }
    }

    @Test fun theSettingScalesTheBudgetTheCeilingsAndTheGamutShare() {
        val base = CoverPaletteSpec()
        for (k in listOf(0.7, 1.25)) {
            val s = CoverPaletteSpec.forIntensity(k)
            assertEquals(k, s.intensity)
            assertEquals(base.accentChromaMax * k, s.accentChromaMax, 1e-12)
            assertEquals(base.accentChromaMin * k, s.accentChromaMin, 1e-12)
            assertEquals(base.lightAccentChromaMax * k, s.lightAccentChromaMax, 1e-12)
            assertEquals(base.groundChromaMax * k, s.groundChromaMax, 1e-12)
            assertEquals(base.darkAmbient.max * k, s.darkAmbient.max, 1e-12)
            assertEquals(base.darkBackgroundTop.max * k, s.darkBackgroundTop.max, 1e-12)
            assertEquals(base.lightBackgroundTop.max * k, s.lightBackgroundTop.max, 1e-12)
            assertEquals(base.darkButtonEnd.max * k, s.darkButtonEnd.max, 1e-12)
            assertEquals(minOf(base.gamutMargin * k, CoverPaletteSpec.MAX_GAMUT_SHARE), s.gamutMargin, 1e-12)
            // what the setting does not touch: the factors of the formulas, the text, the rails themselves
            assertEquals(base.darkBackgroundTop.factor, s.darkBackgroundTop.factor)
            assertEquals(base.darkTextPrimary, s.darkTextPrimary)
            assertEquals(base.lightTextSecondary, s.lightTextSecondary)
            assertEquals(base.darkGroundMaxLuminance, s.darkGroundMaxLuminance)
            assertEquals(base.textContrast, s.textContrast)
            assertEquals(base.uiContrast, s.uiContrast)
            assertEquals(base.darkAccentMinL, s.darkAccentMinL)
            assertEquals(base.darkAccentMaxL, s.darkAccentMaxL)
        }
        assertTrue(CoverPaletteSpec.MAX_GAMUT_SHARE < 1.0, "the accent never touches the gamut edge")
    }

    @Test fun everyRailHoldsOnTenCoversAtEverySetting() {
        for (cover in covers) for (dark in listOf(true, false)) for (k in settings) {
            val p = paletteOf(cover, dark, k)
            val name = "${cover.name} ${if (dark) "dark" else "light"} x$k"
            assertRails(name, p, dark)
            if (p.isFallback) continue
            val spec = CoverPaletteSpec.forIntensity(k)
            val (l, c, h) = oklch(p.accent)
            if (dark) assertTrue(l in 0.755..0.845, "$name: accent L $l outside 0.78..0.84 (0.76..0.82 for a bright cover)")
            else assertTrue(l in 0.455..0.545, "$name: accent L $l outside 0.46..0.54")
            val cap = if (dark) spec.accentChromaMax else spec.lightAccentChromaMax
            assertTrue(c <= cap + 0.006, "$name: accent chroma $c over its ceiling $cap")
            assertTrue(c <= spec.gamutMargin * maxChroma(l, h) + 0.006, "$name: accent chroma $c past ${spec.gamutMargin} of the gamut edge")
        }
    }

    @Test fun calmBalancedAndRichAreVisiblyDifferentOnASaturatedCover() {
        val table = StringBuilder("accent chroma and backgroundTop chroma, Calm / Balanced / Rich:\n")
        for (cover in covers) for (dark in listOf(true, false)) {
            val (calm, balanced, rich) = settings.map { paletteOf(cover, dark, it) }
            table.append("%-12s %-5s accent %.3f %.3f %.3f (Rich - Calm %.3f)  top %.3f %.3f %.3f  ambient %.3f %.3f %.3f\n".format(
                cover.name, if (dark) "dark" else "light",
                chromaOf(calm.accent), chromaOf(balanced.accent), chromaOf(rich.accent), chromaOf(rich.accent) - chromaOf(calm.accent),
                chromaOf(calm.backgroundTop), chromaOf(balanced.backgroundTop), chromaOf(rich.backgroundTop),
                chromaOf(calm.ambient), chromaOf(balanced.ambient), chromaOf(rich.ambient),
            ))
        }
        println(table)
        for (name in listOf("Neon", "Vivid green", "Yellow")) {
            val cover = covers.first { it.name == name }
            for (dark in listOf(true, false)) {
                val (calm, balanced, rich) = settings.map { paletteOf(cover, dark, it) }
                val what = "$name ${if (dark) "dark" else "light"}"
                assertTrue(chromaOf(calm.accent) < chromaOf(balanced.accent), "$what: Calm's accent is calmer than Balanced's")
                assertTrue(chromaOf(balanced.accent) < chromaOf(rich.accent), "$what: Rich's accent is richer than Balanced's")
                assertTrue(chromaOf(rich.accent) - chromaOf(calm.accent) >= 0.04, "$what: Rich and Calm accents differ by at least 0.04 OKLab")
                assertTrue(chromaOf(calm.backgroundTop) < chromaOf(rich.backgroundTop), "$what: the ground follows")
                assertTrue(chromaOf(calm.ambient) < chromaOf(rich.ambient), "$what: the ambient light follows")
            }
        }
    }

    @Test fun aGreyCoverStaysGreyAtEverySetting() {
        for (k in settings) for (dark in listOf(true, false)) {
            val p = CoverPalette.fromPixels(greyRamp(), 32, 32, dark, CoverPaletteSpec.forIntensity(k))
            assertTrue(p.isFallback)
            assertTrue(chromaOf(p.backgroundTop) < 0.012, "grey x$k: the ground stays a warm neutral")
            assertRails("grey ${if (dark) "dark" else "light"} x$k", p, dark)
        }
    }
}
