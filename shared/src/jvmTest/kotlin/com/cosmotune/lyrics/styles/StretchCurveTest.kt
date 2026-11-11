package com.cosmotune.lyrics.styles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StretchCurveTest {
    @Test fun aWordStillToComeIsNarrowAndLight() {
        assertEquals(0f, StretchCurve.amount(1_000, 1_500, 0f, 900))
        assertEquals(62f, StretchCurve.width(0f))
        assertEquals(300, StretchCurve.weight(0f))
    }

    @Test fun thePeakIsTheWidestAndHeaviestAxesAllow() {
        assertEquals(125f, StretchCurve.width(1f))
        assertEquals(900, StretchCurve.weight(1f))
    }

    @Test fun aSungWordRestsOnTheBoardsDoneInstance() {
        val settled = StretchCurve.amount(1_000, 1_500, 1f, 1_500 + StretchCurve.SETTLE_MS)
        assertEquals(StretchCurve.DONE, settled, 0.0001f)
        assertEquals(5, StretchCurve.instance(settled), "an instance exactly, so a sung word never sits between two")
        assertEquals(101.375f, StretchCurve.width(StretchCurve.DONE), 0.01f)
        assertEquals(675, StretchCurve.weight(StretchCurve.DONE))
    }

    @Test fun aShortWordPopsToItsPeakAtOnce() {
        val start = 1_000L
        val end = start + 150
        val popped = StretchCurve.amount(start, end, 0.2f, start + StretchCurve.POP_MS)
        assertEquals(StretchCurve.peakFor(150), popped, 0.001f)
        assertEquals(StretchCurve.SHORT_PEAK, StretchCurve.peakFor(150), 0.001f)
    }

    @Test fun aHeldNoteGrowsWithItsSyllablesForAsLongAsItIsHeld() {
        val start = 1_000L
        val end = start + 2_000
        val early = StretchCurve.amount(start, end, 0.1f, start + StretchCurve.POP_MS)
        val middle = StretchCurve.amount(start, end, 0.5f, start + 1_000)
        val late = StretchCurve.amount(start, end, 0.95f, start + 1_900)
        assertTrue(early < 0.8f && early > StretchCurve.DONE, "a jump to just past the sung words' width: $early")
        assertTrue(early < middle && middle < late, "and wider as it is held: $early, $middle, $late")
        assertTrue(StretchCurve.amount(start, end, 1f, end - 1) > 0.99f, "a carried note reaches the peak")
        // A syllable still being held keeps the word where it is: the growth follows the singing, not the clock.
        assertEquals(StretchCurve.amount(start, end, 0.5f, start + 600), StretchCurve.amount(start, end, 0.5f, start + 1_200), 0.0001f)
    }

    @Test fun theWordBeingSungIsNeverNarrowerThanTheWordsAlreadySung() {
        for (duration in listOf(100L, 400L, 900L, 3_000L)) for (through in listOf(0.0f, 0.3f, 0.9f)) {
            val at = 1_000L + StretchCurve.POP_MS + ((duration - StretchCurve.POP_MS).coerceAtLeast(0) * through).toLong()
            if (at >= 1_000L + duration) continue
            assertTrue(StretchCurve.amount(1_000, 1_000 + duration, through, at) >= StretchCurve.DONE, "$duration ms, $through sung")
        }
    }

    @Test fun theLongerTheNoteTheWiderItGets() {
        assertTrue(StretchCurve.peakFor(1_200) > StretchCurve.peakFor(400))
        assertEquals(1f, StretchCurve.peakFor(5_000))
    }

    @Test fun afterItEndsAWordSettlesSmoothlyFromItsPeakToDone() {
        val start = 1_000L
        val end = start + 1_000
        val peak = StretchCurve.amount(start, end, 1f, end - 1)
        val half = StretchCurve.amount(start, end, 1f, end + StretchCurve.SETTLE_MS / 2)
        assertTrue(half < peak && half > StretchCurve.DONE, "settling: $peak > $half > ${StretchCurve.DONE}")
    }

    @Test fun reducedMotionHoldsEveryWordStill() {
        for (at in listOf(0L, 1_000L, 1_200L, 5_000L)) assertEquals(StretchCurve.DONE, StretchCurve.amount(1_000, 1_500, 0.4f, at, reduced = true))
    }

    @Test fun instancesRunEvenlyAlongThePath() {
        assertEquals(0, StretchCurve.instance(0f))
        assertEquals(StretchCurve.INSTANCES - 1, StretchCurve.instance(1f))
        assertEquals(StretchCurve.INSTANCES - 1, StretchCurve.instance(3f))
        for (i in 0 until StretchCurve.INSTANCES) assertEquals(i, StretchCurve.instance(StretchCurve.instanceAmount(i)))
        assertEquals(4, StretchCurve.instance(0.5f))
    }
}
