package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The landscape Now Playing: two panes, the media on the left. */
class LandscapeMetricsTest {

    @Test
    fun theMediaPaneIsHalfTheWindow() = assertEquals(0.5f, LandscapeMetrics.MEDIA_FRACTION, 0f)

    @Test
    fun theCoverIsAsBigAsThePaneAllowsAndSquare() {
        assertEquals(300f, LandscapeMetrics.coverSideDp(paneWidthDp = 376f, paneHeightDp = 300f), 0f)
        assertEquals(200f, LandscapeMetrics.coverSideDp(paneWidthDp = 200f, paneHeightDp = 340f), 0f)
    }

    @Test
    fun aPaneWithNoRoomGivesNoCoverRatherThanANegativeOne() {
        assertEquals(0f, LandscapeMetrics.coverSideDp(-4f, 300f), 0f)
    }

    @Test
    fun thePlayButtonIsSixtyFourAndTheCoverKeepsItsRadius() {
        assertEquals(64f, LandscapeMetrics.PLAY_BUTTON_DP, 0f)
        assertEquals(28f, LandscapeMetrics.MEDIA_RADIUS_DP, 0f)
    }

    @Test
    fun theTitleIsDisplayS() = assertEquals(TypeScale.DISPLAY_S, LandscapeMetrics.TITLE_STEP)

    @Test
    fun landscapeIsWhatTheLayoutChooserCallsAWindowWiderThanTallAndNarrowerThanEightHundredFortyDp() {
        assertEquals(NowPlayingLayout.LANDSCAPE, NowPlayingLayout.choose(780f, 360f))
        assertEquals(NowPlayingLayout.LANDSCAPE, NowPlayingLayout.choose(839f, 600f))
        // the plan's rule, kept: from 840 dp wide a landscape window is the wide layout, a phone on its side included
        assertEquals(NowPlayingLayout.WIDE, NowPlayingLayout.choose(840f, 700f))
        assertEquals(NowPlayingLayout.WIDE, NowPlayingLayout.choose(851f, 393f))
    }
}
