package com.cosmotune

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The logo's two in-app colours: the exact values, and enough contrast on the background each one is used on. */
class CosmoBrandTest {

    private fun channel(argb: Long, shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double =
        0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun theColoursAreApricotAndInkAndOpaque() {
        assertEquals(0xFFFFB98F, CosmoBrand.APRICOT)
        assertEquals(0xFF0B0C0F, CosmoBrand.INK)
    }

    @Test
    fun apricotReadsOnBlackAndInkReadsOnWhite() {
        assertTrue(contrast(CosmoBrand.APRICOT, 0xFF000000) > 12.0, "apricot on black")
        assertTrue(contrast(CosmoBrand.INK, 0xFFFFFFFF) > 19.0, "ink on white")
    }

    @Test
    fun apricotWouldNotReadOnWhite() {
        // The reason the light theme has its own colour.
        assertTrue(contrast(CosmoBrand.APRICOT, 0xFFFFFFFF) < 2.0, "apricot on white")
    }
}
