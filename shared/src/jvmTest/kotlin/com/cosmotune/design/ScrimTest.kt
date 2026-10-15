package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The video scrim (`01-dynamic-colour.md` section 5, `v2.js` `V2.scrimAlpha`) on an 844 dp screen. */
class ScrimTest {

    private val h = 844f

    @Test
    fun topEdgeIsSeventyTwoPercent() = assertEquals(0.72f, Scrim.alphaAt(0f, h, false), 0.002f)

    @Test
    fun bottomEdgeIsNinetyPercent() = assertEquals(0.90f, Scrim.alphaAt(844f, h, false), 0.002f)

    @Test
    fun theMiddleOfTheCoverIsNearlyClear() {
        // 400 dp is 26 dp into the bottom band (it starts at 844 - 470 = 374): 0.55 x 26 / 90.
        assertEquals(0.159f, Scrim.alphaAt(400f, h, false), 0.002f)
    }

    @Test
    fun theTopBandWalksItsStops() {
        assertEquals(0.64f, Scrim.alphaAt(90f, h, false), 0.002f)
        assertEquals(0.30f, Scrim.alphaAt(150f, h, false), 0.002f)
        assertEquals(0f, Scrim.alphaAt(190f, h, false), 0.002f)
        assertEquals(0f, Scrim.alphaAt(300f, h, false), 0.002f)
    }

    @Test
    fun theBottomBandWalksItsStops() {
        assertEquals(0f, Scrim.alphaAt(374f, h, false), 0.002f)
        assertEquals(0.55f, Scrim.alphaAt(374f + 90f, h, false), 0.002f)
        assertEquals(0.86f, Scrim.alphaAt(374f + 190f, h, false), 0.002f)
        assertEquals(0.90f, Scrim.alphaAt(374f + 470f, h, false), 0.002f)
    }

    @Test
    fun lyricsUseTheirOwnThreeStops() {
        assertEquals(0.70f, Scrim.alphaAt(0f, h, true), 0.002f)
        assertEquals(0.88f, Scrim.alphaAt(253.2f, h, true), 0.002f)
        assertEquals(0.90f, Scrim.alphaAt(844f, h, true), 0.002f)
    }

    @Test
    fun aShortScreenCombinesTheBandsAsOneMinusProductOfTheGaps() {
        // 300 dp tall: the bands overlap. At y = 100 the top band is between its 90 and 150 stops, and the bottom band
        // (which starts at 300 - 470 = -170) is 270 dp in, between its 190 and 470 stops.
        val top = 0.64f + (0.30f - 0.64f) * (100f - 90f) / (150f - 90f)
        val by = 100f - (300f - 470f)
        val bottom = 0.86f + (0.90f - 0.86f) * (by - 190f) / (470f - 190f)
        assertEquals(1f - (1f - top) * (1f - bottom), Scrim.alphaAt(100f, 300f, false), 1e-4f)
    }

    @Test
    fun theStopArraysAreFractionOfBandAlphaPairs() {
        assertEquals(8, Scrim.TOP_STOPS.size)
        assertEquals(8, Scrim.BOTTOM_STOPS.size)
        assertEquals(6, Scrim.LYRICS_STOPS.size)
        assertEquals(190f, Scrim.TOP_BAND_DP)
        assertEquals(470f, Scrim.BOTTOM_BAND_DP)
        // (0, 0.72), (90/190, 0.64), (150/190, 0.30), (1, 0).
        assertEquals(0f, Scrim.TOP_STOPS[0]); assertEquals(0.72f, Scrim.TOP_STOPS[1])
        assertEquals(90f / 190f, Scrim.TOP_STOPS[2], 1e-6f); assertEquals(0.64f, Scrim.TOP_STOPS[3])
        assertEquals(150f / 190f, Scrim.TOP_STOPS[4], 1e-6f); assertEquals(0.30f, Scrim.TOP_STOPS[5])
        assertEquals(1f, Scrim.TOP_STOPS[6]); assertEquals(0f, Scrim.TOP_STOPS[7])
        // (0, 0), (90/470, 0.55), (190/470, 0.86), (1, 0.90).
        assertEquals(0f, Scrim.BOTTOM_STOPS[0]); assertEquals(0f, Scrim.BOTTOM_STOPS[1])
        assertEquals(90f / 470f, Scrim.BOTTOM_STOPS[2], 1e-6f); assertEquals(0.55f, Scrim.BOTTOM_STOPS[3])
        assertEquals(190f / 470f, Scrim.BOTTOM_STOPS[4], 1e-6f); assertEquals(0.86f, Scrim.BOTTOM_STOPS[5])
        assertEquals(1f, Scrim.BOTTOM_STOPS[6]); assertEquals(0.90f, Scrim.BOTTOM_STOPS[7])
        // (0, 0.70), (0.3, 0.88), (1, 0.90).
        assertEquals(0f, Scrim.LYRICS_STOPS[0]); assertEquals(0.70f, Scrim.LYRICS_STOPS[1])
        assertEquals(0.3f, Scrim.LYRICS_STOPS[2]); assertEquals(0.88f, Scrim.LYRICS_STOPS[3])
        assertEquals(1f, Scrim.LYRICS_STOPS[4]); assertEquals(0.90f, Scrim.LYRICS_STOPS[5])
    }

    @Test
    fun theStopArraysAgreeWithAlphaAt() {
        // Evaluating the fraction arrays by hand gives the same alpha as alphaAt (on 844 the bands do not overlap).
        fun at(stops: FloatArray, f: Float): Float {
            for (i in 2 until stops.size step 2) if (f <= stops[i]) {
                val f0 = stops[i - 2]
                val a0 = stops[i - 1]
                val f1 = stops[i]
                val a1 = stops[i + 1]
                return a0 + (a1 - a0) * (f - f0) / (f1 - f0)
            }
            return stops[stops.size - 1]
        }
        for (y in 0..190 step 10) assertEquals(Scrim.alphaAt(y.toFloat(), h, false), at(Scrim.TOP_STOPS, y / 190f), 0.002f, "top $y")
        for (by in 0..470 step 10) {
            assertEquals(Scrim.alphaAt(374f + by, h, false), at(Scrim.BOTTOM_STOPS, by / 470f), 0.002f, "bottom $by")
        }
        for (f in 0..10) {
            assertEquals(Scrim.alphaAt(f / 10f * h, h, true), at(Scrim.LYRICS_STOPS, f / 10f), 0.002f, "lyrics $f")
        }
    }

    @Test
    fun outsideTheScreenTheEdgeValueHolds() {
        assertEquals(0.72f, Scrim.alphaAt(-20f, h, false), 0.002f)
        assertEquals(0.90f, Scrim.alphaAt(900f, h, false), 0.002f)
        assertEquals(0.70f, Scrim.alphaAt(-20f, h, true), 0.002f)
    }
}
