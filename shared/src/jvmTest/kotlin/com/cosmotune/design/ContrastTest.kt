package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2 contrast on ARGB colours (alpha is ignored: the caller composites first). */
class ContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun whiteOnBlackIsTwentyOne() = assertEquals(21.0, Contrast.ratio(white, black), 0.001)

    @Test
    fun equalColoursAreOne() {
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-9)
        assertEquals(1.0, Contrast.ratio(white, white), 1e-9)
    }

    @Test
    fun theFourPointFiveGreyOnWhite() = assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), white), 0.01)

    @Test
    fun theOrderOfTheArgumentsDoesNotMatter() {
        val a = 0xFFFFB98F.toInt()
        val b = 0xFF0B0C0F.toInt()
        assertEquals(Contrast.ratio(a, b), Contrast.ratio(b, a), 0.0)
    }

    @Test
    fun alphaIsIgnored() {
        assertEquals(Contrast.ratio(white, black), Contrast.ratio(0x80FFFFFF.toInt(), 0x00000000), 0.0)
    }

    @Test
    fun theLogoColoursReadOnTheirGrounds() {
        // Apricot on black and ink on white are the two in-app logo colours (CosmoBrandTest holds the same numbers).
        assertTrue(Contrast.ratio(0xFFFFB98F.toInt(), black) > 12.0)
        assertTrue(Contrast.ratio(0xFF0B0C0F.toInt(), white) > 19.0)
    }
}
