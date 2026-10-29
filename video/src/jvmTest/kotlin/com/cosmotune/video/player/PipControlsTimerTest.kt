package com.cosmotune.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the picture-in-picture window's controls show and hide (the pure timing; the window only feeds it the pointer). */
class PipControlsTimerTest {
    private val t0 = 10_000L

    @Test
    fun aWindowThatJustOpenedShowsTheControlsForAMomentThenHidesThemWhileThePointerIsElsewhere() {
        val timer = PipControlsTimer(t0)
        assertTrue(timer.visible(t0))
        assertTrue(timer.visible(t0 + PipControlsTimer.OPEN_MS - 1))
        assertFalse(timer.visible(t0 + PipControlsTimer.OPEN_MS))
        assertNull(timer.nextChange(t0 + PipControlsTimer.OPEN_MS))
    }

    @Test
    fun theControlsShowWhenThePointerEntersAndFadeAfterTheIdleTimeWhilePlaying() {
        val timer = PipControlsTimer(t0)
        val enter = t0 + 5_000
        assertFalse(timer.visible(enter))
        timer.pointerInside(enter, moved = true)
        assertTrue(timer.visible(enter))
        assertEquals(PipControlsTimer.IDLE_MS, timer.nextChange(enter))
        assertTrue(timer.visible(enter + PipControlsTimer.IDLE_MS - 1))
        assertFalse(timer.visible(enter + PipControlsTimer.IDLE_MS))
        assertNull(timer.nextChange(enter + PipControlsTimer.IDLE_MS))
    }

    @Test
    fun movementOrAClickOverTheWindowKeepsThemUpAnotherIdleTime() {
        val timer = PipControlsTimer(t0)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        timer.pointerInside(enter + 1_000, moved = false) // still, over the window: no extension
        assertFalse(timer.visible(enter + PipControlsTimer.IDLE_MS))
        timer.pointerInside(enter + 1_200, moved = true)
        assertTrue(timer.visible(enter + 1_200 + PipControlsTimer.IDLE_MS - 1))
        assertFalse(timer.visible(enter + 1_200 + PipControlsTimer.IDLE_MS))
    }

    @Test
    fun aPausedVideoKeepsThemWhileThePointerIsOverTheWindow() {
        val timer = PipControlsTimer(t0)
        timer.playing(t0, false)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        assertTrue(timer.visible(enter + 60_000))
        assertNull(timer.nextChange(enter + 60_000))
    }

    @Test
    fun startingToPlayOverTheWindowFadesThemAfterTheIdleTimeNotAtOnce() {
        val timer = PipControlsTimer(t0)
        timer.playing(t0, false)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        timer.playing(enter + 10_000, true)
        assertTrue(timer.visible(enter + 10_000))
        assertFalse(timer.visible(enter + 10_000 + PipControlsTimer.IDLE_MS))
    }

    @Test
    fun theyGoQuicklyWhenThePointerLeavesEvenWhilePausedOrJustMoved() {
        val timer = PipControlsTimer(t0)
        timer.playing(t0, false)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        val leave = enter + 100
        timer.pointerOutside(leave)
        assertTrue(timer.visible(leave + PipControlsTimer.LEAVE_MS - 1))
        assertEquals(PipControlsTimer.LEAVE_MS, timer.nextChange(leave))
        assertFalse(timer.visible(leave + PipControlsTimer.LEAVE_MS))
        assertTrue(PipControlsTimer.LEAVE_MS <= 300)
    }

    @Test
    fun comingBackWithinTheLeaveTimeKeepsThemAndAgainShowsThemFully() {
        val timer = PipControlsTimer(t0)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        timer.pointerOutside(enter + 500)
        timer.pointerInside(enter + 600, moved = true)
        assertTrue(timer.visible(enter + 600 + PipControlsTimer.IDLE_MS - 1))
        assertFalse(timer.visible(enter + 600 + PipControlsTimer.IDLE_MS))
    }

    @Test
    fun aLockKeepsThemWhateverThePointerDoesAndReleasingItLeavesAMoment() {
        val timer = PipControlsTimer(t0)
        timer.lock()
        timer.pointerOutside(t0 + 3_000)
        assertTrue(timer.visible(t0 + 100_000), "a drag, a resize or an open menu keeps them, even with the pointer away")
        assertNull(timer.nextChange(t0 + 100_000))
        val released = t0 + 100_000
        timer.unlock(released)
        assertTrue(timer.visible(released + PipControlsTimer.LEAVE_MS - 1))
        assertFalse(timer.visible(released + PipControlsTimer.LEAVE_MS))
    }

    @Test
    fun twoLocksNeedTwoReleases() {
        val timer = PipControlsTimer(t0)
        val enter = t0 + 5_000
        timer.pointerInside(enter, moved = true)
        timer.lock()
        timer.lock()
        timer.unlock(enter + 10)
        assertTrue(timer.visible(enter + 60_000))
        timer.unlock(enter + 20)
        assertFalse(timer.visible(enter + 20 + PipControlsTimer.IDLE_MS))
        timer.unlock(enter + 30) // an extra release is ignored
        assertFalse(timer.visible(enter + 30 + PipControlsTimer.IDLE_MS))
    }

    @Test
    fun theWindowIsHoveredOnlyInsideItsBounds() {
        assertTrue(pointerOverWindow(100, 100, 100, 100, 320, 180))
        assertTrue(pointerOverWindow(419, 279, 100, 100, 320, 180))
        assertFalse(pointerOverWindow(420, 150, 100, 100, 320, 180))
        assertFalse(pointerOverWindow(150, 280, 100, 100, 320, 180))
        assertFalse(pointerOverWindow(99, 150, 100, 100, 320, 180))
        assertFalse(pointerOverWindow(null, null, 100, 100, 320, 180), "no pointer reading: not over it")
    }
}
