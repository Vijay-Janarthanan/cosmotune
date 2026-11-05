package com.cosmotune.ui

import com.cosmotune.ui.theme.Grain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrainTest {

    @Test fun theTileIs128By128OpaqueGrey() {
        val p = Grain.pixels()
        assertEquals(128 * 128, p.size)
        p.forEach {
            val a = it ushr 24
            val r = (it shr 16) and 0xFF
            val g = (it shr 8) and 0xFF
            val b = it and 0xFF
            assertEquals(0xFF, a)
            assertTrue(r == g && g == b, "grey")
        }
    }

    @Test fun theSequenceIsFixed() {
        // seed 1: 1 * 1103515245 + 12345 = 1103527590; 1103527590 ushr 16 = 16838; 16838 and 0xFF = 198
        assertEquals(0xFFC6C6C6.toInt(), Grain.pixels()[0])
        assertContentEquals(Grain.pixels(), Grain.pixels())
    }

    @Test fun theNoiseIsSpreadOverTheWholeRange() {
        val values = Grain.pixels().map { it and 0xFF }
        val mean = values.average()
        assertTrue(mean in 120.0..135.0, "mean $mean")
        assertTrue(values.min() <= 8 && values.max() >= 247, "range ${values.min()}..${values.max()}")
        assertTrue(values.toSet().size > 200, "many distinct values")
    }

    @Test fun theOpacitiesAreTheDesignersAndLighterOnLight() {
        assertEquals(0.045f, Grain.ALPHA_DARK)
        assertEquals(0.03f, Grain.ALPHA_LIGHT)
        assertEquals(Grain.ALPHA_DARK, Grain.alpha(dark = true))
        assertEquals(Grain.ALPHA_LIGHT, Grain.alpha(dark = false))
    }
}
