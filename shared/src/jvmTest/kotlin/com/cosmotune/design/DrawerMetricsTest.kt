package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile drawer's panel runs the whole height of the window, under the status bar and the navigation bar, and keeps its own
 * content clear of both: 8 dp under the status bar, and at least 28 dp over the bottom (more where the navigation bar is taller).
 */
class DrawerMetricsTest {

    @Test
    fun theBottomKeepsTheBoardsTwentyEightDpWhereTheNavigationBarIsLowerAndClearsATallerOne() {
        assertEquals(28f, DrawerMetrics.panelBottomPaddingDp(navBarDp = 0f), "a window without a bar")
        assertEquals(28f, DrawerMetrics.panelBottomPaddingDp(navBarDp = 24f), "gesture navigation")
        assertEquals(28f, DrawerMetrics.panelBottomPaddingDp(navBarDp = 28f))
        assertEquals(48f, DrawerMetrics.panelBottomPaddingDp(navBarDp = 48f), "three buttons")
        assertEquals(64f, DrawerMetrics.panelBottomPaddingDp(navBarDp = 64f), "a tablet's taskbar")
    }

    @Test
    fun theTopStandsEightDpUnderTheStatusBar() {
        assertEquals(8f, DrawerMetrics.panelTopPaddingDp(statusBarDp = 0f))
        assertEquals(8f + 36f, DrawerMetrics.panelTopPaddingDp(statusBarDp = 36f))
    }

    @Test
    fun theContentIsWhatIsLeftOfTheWholeWindowBetweenTheTwoPaddings() {
        // a 952 dp tall phone (1280 x 2856 at 3x): the window height includes both bars
        assertEquals(952f - 44f - 28f, DrawerMetrics.panelContentHeightDp(windowHeightDp = 952f, statusBarDp = 36f, navBarDp = 24f))
        assertEquals(952f - 44f - 48f, DrawerMetrics.panelContentHeightDp(windowHeightDp = 952f, statusBarDp = 36f, navBarDp = 48f))
    }

    @Test
    fun aWindowTooShortForItsPaddingsHasNoNegativeContent() {
        assertEquals(0f, DrawerMetrics.panelContentHeightDp(windowHeightDp = 40f, statusBarDp = 36f, navBarDp = 48f))
    }

    @Test
    fun theBottomPaddingNeverShrinksWhateverTheInsetAndNeverLeavesTheContentUnderTheBar() {
        var nav = 0f
        while (nav <= 120f) {
            val bottom = DrawerMetrics.panelBottomPaddingDp(nav)
            assertTrue(bottom >= nav, "the content clears a bar of $nav dp")
            assertTrue(bottom >= DrawerMetrics.PANEL_BOTTOM_DP, "and keeps its own minimum at $nav dp")
            nav += 1f
        }
    }
}
