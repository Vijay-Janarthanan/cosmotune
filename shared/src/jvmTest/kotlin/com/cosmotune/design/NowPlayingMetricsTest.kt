package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The portrait Now Playing's numbers (`screens-v3.css:131-161`, `V3.nowPlaying`) and the small decisions made from them. */
class NowPlayingMetricsTest {

    // ---- the cover -------------------------------------------------------------------------------------------------

    @Test
    fun theCoverIsThreeHundredTwentyOnAnOrdinaryPhone() =
        assertEquals(320f, NowPlayingMetrics.coverSideDp(windowWidthDp = 390f, windowHeightDp = 844f, stackHeightDp = 330f), 0.001f)

    @Test
    fun aShortWindowShrinksTheCoverSoItNeverRunsUnderTheStack() {
        // 360 x 800 with a 396 dp stack: 800 - 396 - 16 - 112 = 276
        assertEquals(276f, NowPlayingMetrics.coverSideDp(360f, 800f, 396f), 0.001f)
    }

    @Test
    fun aNarrowWindowKeepsTwentyDpOnEachSide() =
        assertEquals(280f, NowPlayingMetrics.coverSideDp(320f, 900f, 330f), 0.001f)

    @Test
    fun theCoverNeverGoesBelowItsFloor() =
        assertEquals(NowPlayingMetrics.COVER_MIN_DP, NowPlayingMetrics.coverSideDp(800f, 360f, 330f), 0.001f)

    @Test
    fun anUnmeasuredStackIsTreatedAsEmpty() =
        assertEquals(320f, NowPlayingMetrics.coverSideDp(390f, 844f, 0f), 0.001f)

    // ---- the reveal: cover into the chip ---------------------------------------------------------------------------

    @Test
    fun theChipSlotOpensFromNothingToFortyDp() {
        assertEquals(0f, NowPlayingMetrics.chipSlotDp(0f), 0.001f)
        assertEquals(20f, NowPlayingMetrics.chipSlotDp(0.5f), 0.001f)
        assertEquals(40f, NowPlayingMetrics.chipSlotDp(1f), 0.001f)
    }

    @Test
    fun theChipSlotHoldsAtTheEndsWhateverTheSpringDoes() {
        assertEquals(0f, NowPlayingMetrics.chipSlotDp(-0.02f), 0.001f)
        assertEquals(40f, NowPlayingMetrics.chipSlotDp(1.03f), 0.001f)
    }

    @Test
    fun theCoverStartsWhereItRestsAndEndsAsBigAsTheChip() {
        assertEquals(1f, NowPlayingMetrics.coverScale(0f, 320f), 0.0001f)
        assertEquals(0.1f, NowPlayingMetrics.coverScale(1f, 320f), 0.0001f)
        assertEquals(32f / 276f, NowPlayingMetrics.coverScale(1f, 276f), 0.0001f)
    }

    @Test
    fun theCoverTravelsFromItsCentreToTheChipCentre() {
        assertEquals(0f, NowPlayingMetrics.travel(0f, from = 195f, to = 36f), 0.001f)
        assertEquals(-159f, NowPlayingMetrics.travel(1f, from = 195f, to = 36f), 0.001f)
        assertEquals(-79.5f, NowPlayingMetrics.travel(0.5f, from = 195f, to = 36f), 0.001f)
    }

    @Test
    fun theSpringMayOvershootTheTravel() =
        assertTrue(NowPlayingMetrics.travel(1.02f, from = 0f, to = 100f) > 100f)

    @Test
    fun theHandOverToTheChipPictureHappensOnlyAtTheEnd() {
        assertEquals(0f, NowPlayingMetrics.chipShare(0f), 0.0001f)
        assertEquals(0f, NowPlayingMetrics.chipShare(0.7f), 0.0001f)
        assertEquals(1f, NowPlayingMetrics.chipShare(1f), 0.0001f)
        assertEquals(1f, NowPlayingMetrics.chipShare(1.04f), 0.0001f)
        assertEquals(0.5f, NowPlayingMetrics.chipShare(0.85f), 0.0001f)
    }

    // ---- the title -------------------------------------------------------------------------------------------------

    @Test
    fun theTitleStepsThroughSixInstances() {
        assertEquals(0, NowPlayingMetrics.titleStep(0f))
        assertEquals(3, NowPlayingMetrics.titleStep(0.5f))
        assertEquals(5, NowPlayingMetrics.titleStep(1f))
    }

    @Test
    fun aSpringOvershootDoesNotLeaveTheInstances() {
        assertEquals(5, NowPlayingMetrics.titleStep(1.04f))
        assertEquals(0, NowPlayingMetrics.titleStep(-0.04f))
    }

    @Test
    fun theTitleTracksFromLooseToTight() {
        assertEquals(0.01f, NowPlayingMetrics.titleTrackingEm(0), 0.00001f)
        assertEquals(-0.022f, NowPlayingMetrics.titleTrackingEm(5), 0.00001f)
        assertTrue(NowPlayingMetrics.titleTrackingEm(2) > NowPlayingMetrics.titleTrackingEm(3))
    }

    // ---- the times under the scrubber ------------------------------------------------------------------------------

    @Test
    fun timesAreMinutesAndTwoDigitSeconds() {
        assertEquals("0:00", NowPlayingMetrics.formatTime(0))
        assertEquals("0:07", NowPlayingMetrics.formatTime(7_999))
        assertEquals("3:05", NowPlayingMetrics.formatTime(185_000))
        assertEquals("61:01", NowPlayingMetrics.formatTime(3_661_000))
    }

    @Test
    fun aNegativeTimeIsZero() = assertEquals("0:00", NowPlayingMetrics.formatTime(-5))

    @Test
    fun whatIsLeftHasAMinusAndNeverGoesBelowZero() {
        assertEquals("-2:00", NowPlayingMetrics.formatRemaining(positionMs = 60_000, durationMs = 180_000))
        assertEquals("-0:00", NowPlayingMetrics.formatRemaining(positionMs = 200_000, durationMs = 180_000))
        assertEquals("-0:00", NowPlayingMetrics.formatRemaining(positionMs = 0, durationMs = 0))
    }

    // ---- which layout -----------------------------------------------------------------------------------------------

    @Test
    fun aPhoneUprightIsPortrait() = assertEquals(NowPlayingLayout.PORTRAIT, NowPlayingLayout.choose(390f, 844f))

    @Test
    fun aPhoneOnItsSideIsLandscape() = assertEquals(NowPlayingLayout.LANDSCAPE, NowPlayingLayout.choose(780f, 360f))

    @Test
    fun aWideWindowIsWide() = assertEquals(NowPlayingLayout.WIDE, NowPlayingLayout.choose(1200f, 800f))

    @Test
    fun theWideLayoutNeedsEightHundredFortyDpAndLandscapeProportions() {
        assertEquals(NowPlayingLayout.WIDE, NowPlayingLayout.choose(840f, 600f))
        assertEquals(NowPlayingLayout.LANDSCAPE, NowPlayingLayout.choose(839f, 600f))
        // tall and wide: still portrait
        assertEquals(NowPlayingLayout.PORTRAIT, NowPlayingLayout.choose(900f, 1200f))
        // square counts as portrait: the width has to be larger
        assertEquals(NowPlayingLayout.PORTRAIT, NowPlayingLayout.choose(700f, 700f))
    }

    // ---- the glow and the stack ------------------------------------------------------------------------------------

    @Test
    fun theGlowIsTheCssRadialGradientOfTheBoard() {
        // radial-gradient(70% 90% at 50% 115%, glow 62%, glow 24% 50%, transparent 78%) on a 200 dp band
        assertEquals(200f, NowPlayingMetrics.GLOW_BAND_DP, 0.001f)
        assertEquals(0.62f, NowPlayingMetrics.GLOW_ALPHA_INNER, 0.0001f)
        assertEquals(0.24f, NowPlayingMetrics.GLOW_ALPHA_MID, 0.0001f)
        assertEquals(0.5f, NowPlayingMetrics.GLOW_MID_AT, 0.0001f)
        assertEquals(0.78f, NowPlayingMetrics.GLOW_END_AT, 0.0001f)
        assertEquals(0.70f, NowPlayingMetrics.GLOW_RX_OF_WIDTH, 0.0001f)
        assertEquals(0.90f, NowPlayingMetrics.GLOW_RY_OF_BAND, 0.0001f)
        assertEquals(1.15f, NowPlayingMetrics.GLOW_CY_OF_BAND, 0.0001f)
    }
}
