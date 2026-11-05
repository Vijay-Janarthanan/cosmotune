package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lyrics view's numbers (`screens-v3.css:157-166`) and the decisions the Compose side makes from them. */
class LyricsMetricsTest {

    // ---- which state a line is in ------------------------------------------------------------------------------------

    @Test
    fun linesBeforeTheCurrentOneArePastAndLinesAfterItAreUpcoming() {
        assertEquals(LyricsMetrics.LineState.PAST, LyricsMetrics.lineState(index = 1, current = 3))
        assertEquals(LyricsMetrics.LineState.CURRENT, LyricsMetrics.lineState(index = 3, current = 3))
        assertEquals(LyricsMetrics.LineState.UPCOMING, LyricsMetrics.lineState(index = 4, current = 3))
    }

    @Test
    fun beforeTheFirstLineNothingIsPastAndNothingIsCurrent() {
        assertEquals(LyricsMetrics.LineState.UPCOMING, LyricsMetrics.lineState(index = 0, current = -1))
        assertEquals(LyricsMetrics.LineState.UPCOMING, LyricsMetrics.lineState(index = 7, current = -1))
    }

    // ---- scale and alpha ---------------------------------------------------------------------------------------------

    @Test
    fun thePortraitScalesAreOnePointTwoAndPointEightySix() {
        assertEquals(1.2f, LyricsMetrics.PORTRAIT_CURRENT_SCALE, 0f)
        assertEquals(0.86f, LyricsMetrics.PORTRAIT_PAST_SCALE, 0f)
        val c = LyricsMetrics.PORTRAIT_CURRENT_SCALE
        val p = LyricsMetrics.PORTRAIT_PAST_SCALE
        assertEquals(1.2f, LyricsMetrics.scaleFor(LyricsMetrics.LineState.CURRENT, c, p, reducedMotion = false), 0f)
        assertEquals(0.86f, LyricsMetrics.scaleFor(LyricsMetrics.LineState.PAST, c, p, reducedMotion = false), 0f)
        assertEquals(1f, LyricsMetrics.scaleFor(LyricsMetrics.LineState.UPCOMING, c, p, reducedMotion = false), 0f)
    }

    @Test
    fun withReducedMotionNothingScales() {
        for (state in LyricsMetrics.LineState.entries) {
            assertEquals(1f, LyricsMetrics.scaleFor(state, 1.2f, 0.86f, reducedMotion = true), 0f, "state $state")
        }
    }

    @Test
    fun pastLinesAreDimmedToEightyPercentAndUpcomingOnesToSeventyEight() {
        assertEquals(0.8f, LyricsMetrics.alphaFor(LyricsMetrics.LineState.PAST), 0f)
        assertEquals(0.78f, LyricsMetrics.alphaFor(LyricsMetrics.LineState.UPCOMING), 0f)
        assertEquals(1f, LyricsMetrics.alphaFor(LyricsMetrics.LineState.CURRENT), 0f)
    }

    // ---- the list ----------------------------------------------------------------------------------------------------

    @Test
    fun theFirstLineStartsFiftyFourDpUnderTheBarWhereTheBoardPutsItOneHundredFiftyFromTheTop() {
        assertEquals(54f, LyricsMetrics.PORTRAIT_TOP_PADDING_DP, 0f)
        assertEquals(14f, LyricsMetrics.LINE_GAP_DP, 0f)
    }

    @Test
    fun aLineIsLaidOutNarrowEnoughToFitWhenItIsScaledUp() {
        // 320 px list, current scale 1.2: laid out at 267 px, drawn at 320 px
        assertEquals(267, LyricsMetrics.layoutWidthPx(320, 1.2f))
        assertTrue(LyricsMetrics.layoutWidthPx(320, 1.2f) * 1.2f <= 320.5f)
    }

    @Test
    fun aScaleBelowOneNeverWidensALineBeyondTheList() {
        assertEquals(320, LyricsMetrics.layoutWidthPx(320, 0.9f))
        assertEquals(320, LyricsMetrics.layoutWidthPx(320, 1f))
    }

    @Test
    fun followingLeavesTheLineOneThirdOfTheListDown() {
        assertEquals(-300, LyricsMetrics.followOffsetPx(900))
        assertEquals(-266, LyricsMetrics.followOffsetPx(800))
        assertEquals(0, LyricsMetrics.followOffsetPx(0))
    }

    @Test
    fun theLastLineHasRoomToRiseToThatThird() {
        // two thirds of the height are needed under the anchor; 0.7 leaves a little over
        assertTrue(LyricsMetrics.bottomRoomPx(900) >= 900 * 2 / 3)
        assertEquals(630, LyricsMetrics.bottomRoomPx(900))
    }

    @Test
    fun aFingerPausesFollowingForTwoAndAHalfSeconds() = assertEquals(2_500L, LyricsMetrics.FOLLOW_PAUSE_MS)

    // ---- the fill ----------------------------------------------------------------------------------------------------

    @Test
    fun theFillIsAFractionalCharacterPosition() {
        assertEquals(0f, LyricsMetrics.sungChars(0f, 34), 0f)
        assertEquals(17f, LyricsMetrics.sungChars(0.5f, 34), 0.0001f)
        assertEquals(34f, LyricsMetrics.sungChars(1f, 34), 0f)
    }

    @Test
    fun theFillNeverLeavesTheLine() {
        assertEquals(0f, LyricsMetrics.sungChars(-0.2f, 34), 0f)
        assertEquals(34f, LyricsMetrics.sungChars(1.4f, 34), 0f)
    }

    @Test
    fun romanisationIsFourteenSpAtWeightFiveHundred() {
        assertEquals(14f, LyricsMetrics.SUB_SIZE_SP, 0f)
        assertEquals(500, LyricsMetrics.SUB_WEIGHT)
    }

    @Test
    fun aPortraitWindowStartsAtTitleLAndALandscapeOneAtTitleM() {
        assertEquals(TypeScale.TITLE_L, LyricsMetrics.PORTRAIT_BASE_STEP)
        assertEquals(TypeScale.TITLE_M, LyricsMetrics.LANDSCAPE_BASE_STEP)
    }
}
