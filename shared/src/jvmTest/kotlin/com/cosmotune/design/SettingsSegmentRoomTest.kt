package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room a settings page's segmented control leaves each label (P4-17: "Balanced" was cut to "Balanc..." under Intensity on a
 * 360 dp phone with the real fonts). A settings segment always shares its row, so it takes the filling control's numbers: 4 dp inside
 * each end, the check (18 dp and a 6 dp gap) only up to three options, and a label that shrinks to 12 sp before it is cut.
 */
class SettingsSegmentRoomTest {
    @Test fun aSegmentSharesItsRowAndKeepsFourDpInsideEachEnd() {
        // Intensity on a 360 dp phone: 360 - 2 x 16 (page) - 70 (under the row's text) - 16 = 242 dp for three segments
        assertEquals((242f - 2 * 3f) / 3f - 2 * 4f, ControlMetrics.settingsSegmentLabelRoomDp(242f, 3, check = false), 0.001f)
    }

    @Test fun theCheckTakesItsWidthAndItsGap() {
        val without = ControlMetrics.settingsSegmentLabelRoomDp(296f, 3, check = false)
        val with = ControlMetrics.settingsSegmentLabelRoomDp(296f, 3, check = true)
        assertEquals(ControlMetrics.SEGMENT_ICON_DP + ControlMetrics.SEGMENT_CHECK_GAP_DP, without - with, 0.001f)
        assertEquals(6f, ControlMetrics.SEGMENT_CHECK_GAP_DP)
    }

    @Test fun theCheckShowsOnTheSelectedSegmentOfAFullSizeControlWithUpToThreeOptions() {
        assertTrue(ControlMetrics.settingsSegmentShowsCheck(selected = true, compact = false, count = 3))
        assertFalse(ControlMetrics.settingsSegmentShowsCheck(selected = false, compact = false, count = 3))
        assertFalse(ControlMetrics.settingsSegmentShowsCheck(selected = true, compact = true, count = 3), "the small control has none")
        assertFalse(ControlMetrics.settingsSegmentShowsCheck(selected = true, compact = false, count = 4), "four options leave no room for it")
    }

    @Test fun theLabelShrinksFromItsOwnSizeToTwelve() {
        assertEquals(14f, ControlMetrics.settingsSegmentLabelMaxSp(compact = false))
        assertEquals(13f, ControlMetrics.settingsSegmentLabelMaxSp(compact = true))
        assertEquals(12f, ControlMetrics.SEGMENT_LABEL_MIN_SP)
    }
}
