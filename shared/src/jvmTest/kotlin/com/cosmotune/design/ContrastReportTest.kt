package com.cosmotune.design

import com.cosmotune.theme.CoverPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The preview's contrast list: what the engine promises, measured with the app's own ratio. */
class ContrastReportTest {
    @Test fun everyLineOfEverySampleCoverPassesItsRailInBothThemes() {
        val failures = buildList {
            for (sample in LookSamples.all) for (dark in listOf(true, false)) {
                val palette = LookSamples.palette(sample, dark)
                for (line in ContrastReport.lines(palette)) {
                    if (!line.passes) add("${sample.name} ${if (dark) "dark" else "light"}: ${line.label} ${"%.2f".format(line.ratio)} < ${line.rail}")
                }
            }
        }
        assertTrue(failures.isEmpty(), "contrast below the rail:\n" + failures.joinToString("\n"))
    }

    @Test fun theReportNamesTheSixChecksWithTheirRails() {
        val lines = ContrastReport.lines(LookSamples.palette(LookSamples.all.first(), dark = true))
        assertEquals(listOf(4.5, 4.5, 4.5, 3.0, 4.5, 4.5), lines.map { it.rail })
        assertEquals(6, lines.map { it.label }.toSet().size)
    }

    @Test fun theRatioIsTheWorstOfTheColoursItIsMeasuredOn() {
        val palette = LookSamples.palette(LookSamples.all.first(), dark = true)
        val line = ContrastReport.lines(palette).first()   // primary text on the grounds and surface
        val grounds = listOf(palette.backgroundTop, palette.backgroundMid, palette.backgroundBottom, palette.surface)
        assertEquals(grounds.minOf { Contrast.ratio(palette.textPrimary, it) }, line.ratio, 1e-9)
    }

    @Test fun aPaletteWithBadTextFailsTheReport() {
        val good = LookSamples.palette(LookSamples.all.first(), dark = true)
        val bad = good.copy(textPrimary = good.backgroundMid)
        assertTrue(ContrastReport.lines(bad).any { !it.passes })
    }

    @Test fun theSixSamplesAreGreenRedOrangeBlueYellowNeonAndPaper() {
        assertEquals(listOf("Green", "Red-orange", "Blue", "Yellow", "Neon", "Paper"), LookSamples.all.map { it.name })
    }

    @Test fun aSampleIsAStripedSixtyFourBySixtyFourImage() {
        val sample = LookSamples.all.first()
        val px = LookSamples.pixels(sample)
        assertEquals(64 * 64, px.size)
        assertEquals(sample.a, px[0])            // column 0 to 7: the first stripe
        assertEquals(sample.b, px[8])            // column 8 to 15: the second
        assertEquals(sample.a, px[16])
        assertEquals(sample.a, px[64 * 5])       // the stripes are vertical: a row below starts the same
    }

    @Test fun aSamplePaletteIsNotTheBrandOne() {
        val brand = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = true)
        val green = LookSamples.palette(LookSamples.all.first(), dark = true)
        assertTrue(green.accent != brand.accent)
    }
}
