package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fitting a short label on whole words (a mood tile's name), and a segmented control that shares its row (Library's four). */
class LabelFitTest {
    @Test fun theSizesRunFromTheLargestDownToTheSmallestAndEndOnIt() {
        assertContentEquals(floatArrayOf(20f, 19f, 18f, 17f, 16f, 15f, 14f, 13f, 12f), LabelFit.sizes(20f, 12f, 1f))
        assertContentEquals(floatArrayOf(14f, 13.5f, 13f, 12.5f, 12f), LabelFit.sizes(14f, 12f, 0.5f))
        // a step that does not land on the floor still ends on it
        assertContentEquals(floatArrayOf(24f, 21f, 18f, 15f, 12.5f), LabelFit.sizes(24f, 12.5f, 3f))
        assertContentEquals(floatArrayOf(12f), LabelFit.sizes(12f, 12f, 1f))
        assertContentEquals(floatArrayOf(12f), LabelFit.sizes(10f, 12f, 1f), "never below the floor")
    }

    @Test fun aLineMayEndOnlyBetweenWords() {
        assertTrue(LabelFit.breaksOnlyAtSpaces("Feel good", intArrayOf(5, 9)), "Feel | good")
        assertTrue(LabelFit.breaksOnlyAtSpaces("Хорошее настроение", intArrayOf(8, 18)))
        assertFalse(LabelFit.breaksOnlyAtSpaces("Energise", intArrayOf(7, 8)), "Energis | e")
        assertFalse(LabelFit.breaksOnlyAtSpaces("Konzentration", intArrayOf(9, 13)), "Konzentrat | ion")
        assertFalse(LabelFit.breaksOnlyAtSpaces("Feel goodness", intArrayOf(5, 10, 13)), "Feel | goodn | ess")
        assertTrue(LabelFit.breaksOnlyAtSpaces("Feel-good", intArrayOf(5, 9)), "after a hyphen is a break the language allows")
        assertTrue(LabelFit.breaksOnlyAtSpaces("Сосредоточенность", intArrayOf(17)), "one line breaks nothing")
        assertTrue(LabelFit.breaksOnlyAtSpaces("", intArrayOf()), "nor does an empty label")
        assertTrue(LabelFit.breaksOnlyAtSpaces("Feel  good", intArrayOf(4, 10)), "a line that ends before the spaces")
    }

    @Test fun aOneWordLabelTakesOneLineAndAnyOtherTwo() {
        assertEquals(1, LabelFit.maxLines("Energise"))
        assertEquals(1, LabelFit.maxLines("  Сосредоточенность "))
        assertEquals(2, LabelFit.maxLines("Feel good"))
        assertEquals(2, LabelFit.maxLines("Хорошее настроение"))
        assertEquals(2, LabelFit.maxLines("Music for a long drive home"))
    }

    @Test fun theMoodLabelShrinksToTwelveSpAtMost() {
        assertEquals(12f, BlockMetrics.MOOD_LABEL_MIN_SP)
        assertEquals(1f, BlockMetrics.MOOD_LABEL_STEP_SP)
    }

    @Test fun fourSegmentsShareA360DpRowWithoutScrollingAndKeepTheirTarget() {
        // Library's four segments, 16 dp in from each side of a 360 dp phone (and of the narrowest, 320 dp)
        for (window in listOf(320f, 360f, 411f)) {
            val row = window - 2 * 16f
            val each = ControlMetrics.fillSegmentWidthDp(row, 4)
            assertEquals(row, each * 4 + ControlMetrics.SEGMENT_GAP_DP * 3, 1e-3f, "the four and their gaps fill the row exactly at $window dp")
            assertTrue(each >= 48f, "a segment keeps a 48 dp target at $window dp: $each")
            assertTrue(each - 2 * ControlMetrics.SEGMENT_FILL_PADDING_DP >= 56f, "room for the label at $window dp")
        }
        assertEquals(4f, ControlMetrics.SEGMENT_FILL_PADDING_DP)
        assertEquals(12f, ControlMetrics.SEGMENT_LABEL_MIN_SP)
    }

    @Test fun aFillingControlDropsTheCheckPastThreeOptionsAndTheOthersKeepIt() {
        assertTrue(ControlMetrics.segmentShowsCheck(count = 4, fill = false), "the classic control is unchanged")
        assertTrue(ControlMetrics.segmentShowsCheck(count = 3, fill = true))
        assertFalse(ControlMetrics.segmentShowsCheck(count = 4, fill = true), "four labels need the check's room")
    }
}
