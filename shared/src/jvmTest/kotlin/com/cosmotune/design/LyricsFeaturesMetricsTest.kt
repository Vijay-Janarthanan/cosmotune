package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers and small decisions behind the features the new lyrics view carries over from the old one (P3-13): duets, the
 * background voice, the romanisation that opens under a line, the word effects. The Compose side only draws what these say.
 */
class LyricsFeaturesMetricsTest {

    // ---- duets -------------------------------------------------------------------------------------------------------

    @Test
    fun theLaneKeptClearOnTheFarSideOfADuetLineIsFortyFourDp() = assertEquals(44f, LyricsMetrics.DUET_LANE_DP, 0f)

    @Test
    fun aLineIsLaidOutInWhatIsLeftOfTheListAfterTheLaneAndThenNarrowedForItsLargestScale() {
        // 400 px list, 88 px lane (44 dp at density 2), scale 1.2: (400 - 88) / 1.2 = 260
        assertEquals(260, LyricsMetrics.layoutWidthPx(400, 88, 1.2f))
        // drawn at the largest scale it fills exactly what is left after the lane
        assertTrue(LyricsMetrics.layoutWidthPx(400, 88, 1.2f) * 1.2f <= 400f - 88f + 0.5f)
    }

    @Test
    fun noLaneIsTheSameAsTheOldWidth() {
        assertEquals(LyricsMetrics.layoutWidthPx(320, 1.2f), LyricsMetrics.layoutWidthPx(320, 0, 1.2f))
        assertEquals(LyricsMetrics.layoutWidthPx(320, 0.9f), LyricsMetrics.layoutWidthPx(320, 0, 0.9f))
    }

    @Test
    fun aLaneWiderThanTheListLeavesNothingNegative() {
        assertEquals(0, LyricsMetrics.layoutWidthPx(100, 400, 1.2f))
    }

    @Test
    fun aLineScalesFromTheEdgeItIsWrittenFrom() {
        // left to right: the start side scales from the left, the end side from the right
        assertEquals(0f, LyricsMetrics.originFraction(onEnd = false, rtl = false), 0f)
        assertEquals(1f, LyricsMetrics.originFraction(onEnd = true, rtl = false), 0f)
        // right to left: the start side is the right one
        assertEquals(1f, LyricsMetrics.originFraction(onEnd = false, rtl = true), 0f)
        assertEquals(0f, LyricsMetrics.originFraction(onEnd = true, rtl = true), 0f)
    }

    @Test
    fun aLineOnTheEndSideIsPlacedAgainstTheEndEdgeOfTheList() {
        assertEquals(0, LyricsMetrics.placeOffsetPx(400, 260, onEnd = false))
        assertEquals(140, LyricsMetrics.placeOffsetPx(400, 260, onEnd = true))
        assertEquals(0, LyricsMetrics.placeOffsetPx(400, 400, onEnd = true))
        assertEquals(0, LyricsMetrics.placeOffsetPx(300, 400, onEnd = true), "never off the start edge")
    }

    // ---- the background voice -----------------------------------------------------------------------------------------

    @Test
    fun theBackgroundVoiceIsTwoStepsDownAndNeverBelowTitleS() {
        assertEquals(TypeScale.TITLE_S, LyricsMetrics.backingStep(TypeScale.TITLE_L))
        assertEquals(TypeScale.TITLE_S, LyricsMetrics.backingStep(TypeScale.TITLE_M))
        assertEquals(TypeScale.TITLE_S, LyricsMetrics.backingStep(TypeScale.TITLE_S))
        assertEquals(TypeScale.TITLE_M, LyricsMetrics.backingStep(TypeScale.DISPLAY_S))
    }

    @Test
    fun theBackgroundVoiceIsDimmerAndOpensJustAheadOfItsFirstWord() {
        assertEquals(0.72f, LyricsMetrics.BACKING_ALPHA, 0f)
        assertEquals(250L, LyricsMetrics.BACKING_LEAD_MS)
        assertTrue(LyricsMetrics.BACKING_ALPHA < LyricsMetrics.UPCOMING_ALPHA, "dimmer than a line still to come")
    }

    @Test
    fun theBackgroundVoiceFadesInAheadOfTheRoomBeingMade() {
        assertEquals(0f, LyricsMetrics.backingFade(0f), 0f)
        assertEquals(0.8f, LyricsMetrics.backingFade(0.5f), 1e-6f)
        assertEquals(1f, LyricsMetrics.backingFade(0.625f), 1e-6f)
        assertEquals(1f, LyricsMetrics.backingFade(1f), 0f)
        assertEquals(0f, LyricsMetrics.backingFade(-0.2f), 0f)
        assertEquals(1f, LyricsMetrics.backingFade(1.2f), 0f)
    }

    @Test
    fun theBackgroundVoiceGrowsFromAHairSmallerAndRisesTheLastFewDp() {
        assertEquals(0.94f, LyricsMetrics.backingScale(0f), 1e-6f)
        assertEquals(1f, LyricsMetrics.backingScale(1f), 1e-6f)
        assertEquals(1f, LyricsMetrics.backingScale(1.3f), 1e-6f, "a spring that overshoots does not blow it up")
        assertEquals(6f, LyricsMetrics.backingRiseDp(0f), 1e-6f)
        assertEquals(0f, LyricsMetrics.backingRiseDp(1f), 1e-6f)
        assertEquals(3f, LyricsMetrics.backingRiseDp(0.5f), 1e-6f)
    }

    @Test
    fun theTranslationOfABackgroundVoiceIsSmallerThanTheOneUnderALead() {
        assertTrue(LyricsMetrics.SUB_BACKING_SIZE_SP < LyricsMetrics.SUB_SIZE_SP)
        assertEquals(12f, LyricsMetrics.SUB_BACKING_SIZE_SP, 0f)
    }

    // ---- the romanisation under a line ----------------------------------------------------------------------------------

    @Test
    fun theSubLineOpensOutOfTheLineAboveItAndFadesUpAsItDoes() {
        assertEquals(0, LyricsMetrics.subRevealHeightPx(100, 0f))
        assertEquals(50, LyricsMetrics.subRevealHeightPx(100, 0.5f))
        assertEquals(100, LyricsMetrics.subRevealHeightPx(100, 1f))
        assertEquals(100, LyricsMetrics.subRevealHeightPx(100, 1.2f), "a spring that overshoots never makes it taller than it is")
        assertEquals(0, LyricsMetrics.subRevealHeightPx(100, -0.1f))
        assertEquals(-60f, LyricsMetrics.subRevealShiftPx(100, 0f), 1e-4f, "starts 0.6 of its height up, behind the line above")
        assertEquals(-30f, LyricsMetrics.subRevealShiftPx(100, 0.5f), 1e-4f)
        assertEquals(0f, LyricsMetrics.subRevealShiftPx(100, 1f), 1e-4f)
        assertEquals(0f, LyricsMetrics.subRevealAlpha(0f), 0f)
        assertEquals(0.25f, LyricsMetrics.subRevealAlpha(0.5f), 1e-6f)
        assertEquals(1f, LyricsMetrics.subRevealAlpha(1f), 0f)
    }

    // ---- a line under a finger ---------------------------------------------------------------------------------------------

    @Test
    fun aLineUnderAFingerDipsAndWithReducedMotionItDoesNot() {
        assertEquals(0.96f, LyricsMetrics.pressedScale(pressed = true, reducedMotion = false), 0f)
        assertEquals(1f, LyricsMetrics.pressedScale(pressed = false, reducedMotion = false), 0f)
        assertEquals(1f, LyricsMetrics.pressedScale(pressed = true, reducedMotion = true), 0f)
    }

    // ---- the word effects -------------------------------------------------------------------------------------------

    @Test
    fun theLeadingEdgeOfTheFillIsFeatheredOverThirtyDpAndTheWordLiftsTwo() {
        assertEquals(30f, LyricsMetrics.WIPE_FEATHER_DP, 0f)
        assertEquals(2f, LyricsMetrics.WORD_RISE_DP, 0f)
    }

    @Test
    fun theFeatherIsSqueezedWhileTheFillIsStillShorterThanIt() {
        assertEquals(30f, LyricsMetrics.featherWidthPx(30f, 100f), 0f)
        assertEquals(12f, LyricsMetrics.featherWidthPx(30f, 12f), 0f)
        assertEquals(0f, LyricsMetrics.featherWidthPx(30f, -4f), 0f)
    }

    @Test
    fun theBloomBehindAHeldNoteIsSixTenthsOfFullAndKeepsTenDpAroundTheLine() {
        assertEquals(0.62f, LyricsMetrics.GLOW_ALPHA, 0f)
        assertEquals(6f, LyricsMetrics.GLOW_RADIUS_DP, 0f)
        assertEquals(10f, LyricsMetrics.GLOW_ROOM_DP, 0f)
    }

    // ---- a line that is still being sung although the next has begun ---------------------------------------------------

    @Test
    fun aLineStillBeingSungCountsAsCurrentWhateverItsIndex() {
        assertEquals(LyricsMetrics.LineState.CURRENT, LyricsMetrics.lineState(index = 1, current = 3, active = true))
        assertEquals(LyricsMetrics.LineState.PAST, LyricsMetrics.lineState(index = 1, current = 3, active = false))
        assertEquals(LyricsMetrics.LineState.CURRENT, LyricsMetrics.lineState(index = 3, current = 3, active = false))
        assertEquals(LyricsMetrics.LineState.UPCOMING, LyricsMetrics.lineState(index = 5, current = 3, active = false))
        assertEquals(LyricsMetrics.LineState.UPCOMING, LyricsMetrics.lineState(index = 2, current = -1, active = false), "nothing is sung before the first line")
    }
}
