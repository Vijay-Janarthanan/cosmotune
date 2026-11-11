package com.cosmotune.lyrics.styles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StageFitTest {
    @Test fun stretchRowsLeaveRoomForAnyOneWordToGrowToItsPeak() {
        val settled = floatArrayOf(40f, 40f, 40f, 40f)
        val peak = floatArrayOf(60f, 60f, 90f, 60f)
        val rows = IntArray(4)
        // 40 + 10 + 40 + 10 + 40 = 140, plus the third word's 50 of growth = 190: fits 200; a fourth would not.
        val count = StageFit.stretchRows(settled, peak, 4, 10f, 200f, rows)
        assertEquals(2, count)
        assertEquals(0, rows[0])
        assertEquals(3, rows[1])
    }

    @Test fun aWordTooWideForAnyRowGetsOneOfItsOwn() {
        val rows = IntArray(3)
        val count = StageFit.stretchRows(floatArrayOf(300f, 20f, 20f), floatArrayOf(400f, 30f, 30f), 3, 5f, 200f, rows)
        assertEquals(2, count)
        assertEquals(1, rows[1])
    }

    @Test fun posterSetsAShortLineOneWordToARowAsLargeAsTheFrameTakes() {
        val widths = floatArrayOf(100f, 200f, 50f) // at size 100
        val rows = IntArray(3)
        val fit = StageFit.posterSize(widths, 3, 25f, 100f, 0.86f, boxWidth = 400f, boxHeight = 600f, minSize = 10f, maxSize = 400f, rowStart = rows)
        assertTrue(fit.onePerRow)
        assertEquals(3, fit.rows)
        // The widest word (200 at 100) fills the 400 width at 200; three rows of 0.86 * 200 fit 600.
        assertEquals(200f, fit.size, 0.5f)
        assertEquals(listOf(0, 1, 2), rows.toList())
    }

    @Test fun posterPacksALongLineWhenOneWordToARowWouldMakeItTiny() {
        val widths = FloatArray(12) { 60f }
        val rows = IntArray(12)
        val fit = StageFit.posterSize(widths, 12, 20f, 100f, 0.86f, boxWidth = 600f, boxHeight = 400f, minSize = 10f, maxSize = 400f, rowStart = rows)
        assertFalse(fit.onePerRow)
        assertTrue(fit.rows < 12)
        assertTrue(fit.rows * fit.size * 0.86f <= 400f + 0.5f, "the rows fit the height")
        assertEquals(0, rows[0])
    }

    @Test fun posterNeverGoesOverItsLargestSize() {
        val rows = IntArray(1)
        val fit = StageFit.posterSize(floatArrayOf(10f), 1, 5f, 100f, 0.86f, 1_000f, 1_000f, 10f, 120f, rows)
        assertEquals(120f, fit.size)
    }

    @Test fun packingFillsEachRowBeforeStartingTheNext() {
        val rows = IntArray(5)
        assertEquals(3, StageFit.packRows(floatArrayOf(30f, 30f, 30f, 30f, 30f), 5, 10f, 75f, false, rows))
        assertEquals(listOf(0, 2, 4), rows.take(3))
        assertEquals(5, StageFit.packRows(floatArrayOf(30f, 30f, 30f, 30f, 30f), 5, 10f, 75f, true, rows))
    }
}
