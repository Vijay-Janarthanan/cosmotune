package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The floating chrome's metrics (`02-design-formulas.md` section 2: "bottoms at 16 and 90"). */
class ShellMetricsTest {

    @Test
    fun theWorkedExampleBottomsAreSixteenAndNinety() {
        val tabBottom = ShellMetrics.bottomOffsetDp(0f)
        assertEquals(16f, tabBottom)
        assertEquals(90f, tabBottom + ShellMetrics.TAB_BAR_HEIGHT_DP + ShellMetrics.CAPSULE_GAP_DP)
    }

    @Test
    fun theBottomOffsetClearsTheNavigationInsetByEightDp() {
        assertEquals(16f, ShellMetrics.bottomOffsetDp(0f))
        assertEquals(16f, ShellMetrics.bottomOffsetDp(8f))
        assertEquals(32f, ShellMetrics.bottomOffsetDp(24f))
        assertEquals(56f, ShellMetrics.bottomOffsetDp(48f))
    }

    @Test
    fun theChromeHeightAddsTheTabCapsuleAndTheMiniCapsule() {
        assertEquals(80f, ShellMetrics.phoneChromeHeightDp(0f, false))
        assertEquals(154f, ShellMetrics.phoneChromeHeightDp(0f, true))
        assertEquals(120f, ShellMetrics.phoneChromeHeightDp(48f, false))
        assertEquals(194f, ShellMetrics.phoneChromeHeightDp(48f, true))
    }

    @Test
    fun theDesktopBarFootprintIsItsHeightPlusBothMargins() {
        assertEquals(108f, ShellMetrics.desktopBarFootprintDp())
    }

    @Test
    fun theConstantsAreTheDesignersValues() {
        assertEquals(16f, ShellMetrics.SIDE_MARGIN_DP)
        assertEquals(64f, ShellMetrics.TAB_BAR_HEIGHT_DP)
        assertEquals(64f, ShellMetrics.CAPSULE_HEIGHT_DP)
        assertEquals(22f, ShellMetrics.CAPSULE_RADIUS_DP)
        assertEquals(10f, ShellMetrics.CAPSULE_GAP_DP)
        assertEquals(16f, ShellMetrics.MIN_BOTTOM_DP)
        assertEquals(8f, ShellMetrics.INSET_GAP_DP)
        assertEquals(80f, ShellMetrics.DESKTOP_BAR_HEIGHT_DP)
        assertEquals(14f, ShellMetrics.DESKTOP_BAR_MARGIN_DP)
        assertEquals(220f, ShellMetrics.DESKTOP_SIDEBAR_DP)
        assertEquals(360f, ShellMetrics.DESKTOP_SIDE_PANEL_DP)
        assertEquals(980f, ShellMetrics.DESKTOP_COMPACT_DP)
    }
}
