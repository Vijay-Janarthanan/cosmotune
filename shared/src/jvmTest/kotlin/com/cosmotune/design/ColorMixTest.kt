package com.cosmotune.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `mix(a, b, t)` of the component spec: OKLab interpolation, alpha premultiplied (the same as the palette cross-fade). */
class ColorMixTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun channel(argb: Int, shift: Int) = (argb ushr shift) and 0xFF

    private fun near(expected: Int, actual: Int, eps: Int, what: String) = assertTrue(abs(expected - actual) <= eps, "$what: $expected vs $actual")

    @Test
    fun theEndsAreExact() {
        val a = 0xFFD4552E.toInt()
        val b = 0xFF2D5A8A.toInt()
        assertEquals(a, ColorMix.mix(a, b, 0f))
        assertEquals(b, ColorMix.mix(a, b, 1f))
        assertEquals(a, ColorMix.mix(a, b, -0.4f), "t below 0 is clamped")
        assertEquals(b, ColorMix.mix(a, b, 1.7f), "t above 1 is clamped")
    }

    @Test
    fun theMiddleOfBlackAndWhiteIsTheOklabGreyNotTheSrgbOne() {
        // OKLab L = 0.5 is linear 0.125, which is sRGB 0.3887 -> 99; an sRGB mix would give 127 or 128.
        val mid = ColorMix.mix(black, white, 0.5f)
        for (shift in intArrayOf(16, 8, 0)) near(99, channel(mid, shift), 1, "channel at $shift")
        assertEquals(0xFF, channel(mid, 24))
    }

    @Test
    fun aTranslucentColourContributesNoColourWhereItIsNotThere() {
        // transparent red to opaque blue, half way: half opaque, and pure blue (red has no weight where it is invisible)
        val mid = ColorMix.mix(0x00FF0000, 0xFF0000FF.toInt(), 0.5f)
        near(128, channel(mid, 24), 1, "alpha")
        near(0, channel(mid, 16), 2, "red")
        near(0, channel(mid, 8), 2, "green")
        near(255, channel(mid, 0), 2, "blue")
    }

    @Test
    fun twoInvisibleColoursStayInvisible() {
        assertEquals(0, channel(ColorMix.mix(0x00112233, 0x00445566, 0.5f), 24))
    }

    @Test
    fun withAlphaKeepsTheColourAndReplacesTheOpacity() {
        val c = ColorMix.withAlpha(0xFF336699.toInt(), 0.52f)
        assertEquals(0x336699, c and 0xFFFFFF)
        near(133, channel(c, 24), 1, "52 % of 255")
        assertEquals(0xFF, channel(ColorMix.withAlpha(0x00336699, 1f), 24))
        assertEquals(0, channel(ColorMix.withAlpha(0xFF336699.toInt(), 0f), 24))
    }

    @Test
    fun stopsRunFromOneEndToTheOtherAndTheMiddleIsTheMix() {
        val stops = ColorMix.stops(black, white, 5)
        assertEquals(5, stops.size)
        assertEquals(black, stops.first())
        assertEquals(white, stops.last())
        assertEquals(ColorMix.mix(black, white, 0.5f), stops[2])
        assertEquals(ColorMix.mix(black, white, 0.25f), stops[1])
        for (i in 1 until stops.size) assertTrue(channel(stops[i], 8) > channel(stops[i - 1], 8), "lighter at every stop")
    }

    @Test
    fun aSingleStopIsTheStartColour() {
        assertEquals(listOf(black), ColorMix.stops(black, white, 1).toList())
    }
}
