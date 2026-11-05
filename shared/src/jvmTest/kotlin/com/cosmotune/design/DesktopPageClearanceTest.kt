package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the desktop page leaves under its scrolling content for the chrome that floats over it: the player bar in a wide window
 * (only while a song is selected), the tab capsule and the mini player in a compact one. Nothing floats, nothing is reserved: the
 * page's content then scrolls to the window's own bottom edge.
 */
class DesktopPageClearanceTest {

    @Test
    fun aWideWindowWithoutASongReservesNothingBecauseNoBarFloatsOverThePage() {
        assertEquals(0f, ShellMetrics.desktopPageBottomDp(compact = false, songShown = false))
    }

    @Test
    fun aWideWindowWithASongReservesTheBarsFootprint() {
        assertEquals(108f, ShellMetrics.desktopPageBottomDp(compact = false, songShown = true), "80 dp for the bar and 14 dp above and below it")
        assertEquals(ShellMetrics.desktopBarFootprintDp(), ShellMetrics.desktopPageBottomDp(compact = false, songShown = true))
    }

    @Test
    fun aCompactWindowAlwaysHasTheTabCapsuleAndAddsTheMiniPlayerWithASong() {
        assertEquals(80f, ShellMetrics.desktopPageBottomDp(compact = true, songShown = false), "16 dp off the edge and the 64 dp capsule")
        assertEquals(154f, ShellMetrics.desktopPageBottomDp(compact = true, songShown = true), "and 10 + 64 dp for the mini player")
        assertEquals(ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = false), ShellMetrics.desktopPageBottomDp(compact = true, songShown = false))
        assertEquals(ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true), ShellMetrics.desktopPageBottomDp(compact = true, songShown = true))
    }

    @Test
    fun aSongNeverTakesRoomAwayAndNothingIsNegative() {
        for (compact in listOf(false, true)) {
            val without = ShellMetrics.desktopPageBottomDp(compact, songShown = false)
            val with = ShellMetrics.desktopPageBottomDp(compact, songShown = true)
            assertTrue(without >= 0f && with >= without, "compact=$compact: $without, $with")
        }
    }
}
