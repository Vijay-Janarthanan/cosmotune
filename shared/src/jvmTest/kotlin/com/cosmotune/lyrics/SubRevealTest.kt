package com.cosmotune.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the romanisation or translation under each line does when it is asked for, switched, or put away: the old panel's
 * `rememberSubLyricsReveal` as a plain decision. The words are held through the closing, so they fold away instead of vanishing.
 */
class SubRevealTest {
    private fun plan(
        reduced: Boolean = false,
        hasTarget: Boolean,
        hasShown: Boolean,
        sameAsShown: Boolean = false,
        progress: Float = 0f,
    ) = SubReveal.plan(reduced, hasTarget, hasShown, sameAsShown, progress)

    @Test
    fun withReducedMotionItIsThereOrNotWithNoMovement() {
        assertEquals(SubReveal.Plan.SNAP_OPEN, plan(reduced = true, hasTarget = true, hasShown = false))
        assertEquals(SubReveal.Plan.SNAP_OPEN, plan(reduced = true, hasTarget = true, hasShown = true, progress = 1f))
        assertEquals(SubReveal.Plan.SNAP_CLOSED, plan(reduced = true, hasTarget = false, hasShown = true, progress = 1f))
    }

    @Test
    fun askedForWithNothingShownItOpens() {
        assertEquals(SubReveal.Plan.OPEN, plan(hasTarget = true, hasShown = false))
    }

    @Test
    fun theSameWordsShownAgainJustKeepOpen() {
        assertEquals(SubReveal.Plan.OPEN, plan(hasTarget = true, hasShown = true, sameAsShown = true, progress = 1f))
    }

    @Test
    fun switchingFromOneScriptToTheOtherClosesTheFirstBeforeOpeningTheSecond() {
        assertEquals(SubReveal.Plan.CLOSE_THEN_OPEN, plan(hasTarget = true, hasShown = true, sameAsShown = false, progress = 1f))
        assertEquals(SubReveal.Plan.CLOSE_THEN_OPEN, plan(hasTarget = true, hasShown = true, sameAsShown = false, progress = 0.3f))
    }

    @Test
    fun switchingWhileTheFirstIsStillShutJustOpensTheSecond() {
        assertEquals(SubReveal.Plan.OPEN, plan(hasTarget = true, hasShown = true, sameAsShown = false, progress = 0f))
    }

    @Test
    fun putAwayItFoldsAndThenIsForgotten() {
        assertEquals(SubReveal.Plan.CLOSE_THEN_CLEAR, plan(hasTarget = false, hasShown = true, progress = 1f))
    }

    @Test
    fun putAwayWhenItWasNeverOpenThereIsNothingToFold() {
        assertEquals(SubReveal.Plan.SNAP_CLOSED, plan(hasTarget = false, hasShown = false))
        assertEquals(SubReveal.Plan.SNAP_CLOSED, plan(hasTarget = false, hasShown = true, progress = 0f))
    }
}
