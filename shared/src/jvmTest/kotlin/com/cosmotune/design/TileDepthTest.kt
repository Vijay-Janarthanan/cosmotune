package com.cosmotune.design

import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.CoverPaletteSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What lifts a tile off the page: the board's `--e1` and `--hl` numbers, and the text on every tile against its fill. */
class TileDepthTest {
    @Test fun theRimAndTheHairlineAreTheBoardsHighlight() {
        assertEquals(0.10f, TileDepth.rimAlpha(dark = true))
        assertEquals(0.80f, TileDepth.rimAlpha(dark = false))
        assertEquals(1f, TileDepth.RIM_DP)
        assertEquals(0.5f, TileDepth.HAIRLINE_DP)
        // the board's `inset 0 0 0 .5px`: white 7 % (dark), black 6 % (light), which is the palette's outline token halved
        assertEquals(0.07f, TileDepth.hairlineAlpha(dark = true))
        assertEquals(0.06f, TileDepth.hairlineAlpha(dark = false))
        val dark = LookSamples.palette(LookSamples.all[0], dark = true)
        val light = LookSamples.palette(LookSamples.all[0], dark = false)
        assertEquals(TileDepth.hairlineAlpha(true).toDouble(), (dark.outline ushr 24) / 255.0 / 2, 0.003)
        assertEquals(TileDepth.hairlineAlpha(false).toDouble(), (light.outline ushr 24) / 255.0 / 2, 0.003)
    }

    @Test fun theLiftIsTheBoardsFirstElevation() {
        assertEquals(6f, TileDepth.ELEVATION_DP)
        assertEquals(0.28f, TileDepth.AMBIENT_ALPHA)
    }

    @Test fun theTextOfEveryTileReadsOnItsFillOnEverySampleCoverAtEveryIntensity() {
        val failures = buildList {
            for (sample in LookSamples.all) for (dark in listOf(true, false)) for (intensity in listOf(0.7, 1.0, 1.25)) {
                val palette = CoverPalette.fromPixels(LookSamples.pixels(sample), LookSamples.SIZE, LookSamples.SIZE, dark, CoverPaletteSpec.forIntensity(intensity))
                for (line in TileDepth.textLines(palette)) {
                    if (!line.passes) add("${sample.name} ${if (dark) "dark" else "light"} x$intensity: ${line.label} ${"%.2f".format(line.ratio)} < ${line.rail}")
                }
            }
        }
        assertTrue(failures.isEmpty(), "tile text below 4.5:1:\n" + failures.joinToString("\n"))
    }

    @Test fun theListNamesSixChecksAllAtTheTextRail() {
        val lines = TileDepth.textLines(LookSamples.palette(LookSamples.all[1], dark = false))
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.rail == ContrastReport.TEXT_RAIL })
        assertEquals(6, lines.map { it.label }.toSet().size)
    }

    @Test fun aTileWhoseTextMeltsIntoItsFillFailsTheList() {
        val good = LookSamples.palette(LookSamples.all[0], dark = false)
        assertTrue(TileDepth.textLines(good.copy(textSecondary = good.surface)).any { !it.passes })
    }
}
