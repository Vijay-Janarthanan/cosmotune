package com.cosmotune.music.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer's two modes and the boundary between them.
 *
 * A timer that quietly does not run is indistinguishable from one that has not
 * fired yet, so its state has to be checkable rather than watched.
 */
class DesktopSleepTimerTest {

    @AfterTest
    fun tearDown() {
        DesktopSleepTimer.cancel()
        DesktopSleepTimer.nanoTime = System::nanoTime
    }

    private var now = 5_000_000_000L
    private fun useTestClock() { DesktopSleepTimer.nanoTime = { now } }
    private fun advanceMinutes(minutes: Long) { now += minutes * 60L * 1_000_000_000L }

    @Test
    fun nothingIsSetUntilSomethingStartsIt() {
        DesktopSleepTimer.cancel()
        assertNull(DesktopSleepTimer.minutes.value)
        assertNull(DesktopSleepTimer.remainingMs())
        assertFalse(DesktopSleepTimer.afterTrack.value)
        assertFalse(DesktopSleepTimer.isExpired())
    }

    @Test
    fun aDurationCountsDownFromWhatWasAsked() {
        DesktopSleepTimer.start(30)
        assertEquals(30, DesktopSleepTimer.minutes.value)
        val remaining = DesktopSleepTimer.remainingMs()!!
        // Within a second of the full half hour, and never over it.
        assertTrue(remaining in 29 * 60_000L..30 * 60_000L, "remaining was $remaining")
        assertFalse(DesktopSleepTimer.isExpired())
    }

    @Test
    fun theTwoModesDisplaceEachOther() {
        // Both at once has no meaning: one stops at a time, the other at the end
        // of the track, and the UI shows a single line either way.
        DesktopSleepTimer.start(15)
        DesktopSleepTimer.startAfterTrack()
        assertTrue(DesktopSleepTimer.afterTrack.value)
        assertNull(DesktopSleepTimer.minutes.value)
        assertNull(DesktopSleepTimer.remainingMs())

        DesktopSleepTimer.start(15)
        assertFalse(DesktopSleepTimer.afterTrack.value)
        assertEquals(15, DesktopSleepTimer.minutes.value)
    }

    @Test
    fun cancellingClearsBoth() {
        DesktopSleepTimer.startAfterTrack()
        DesktopSleepTimer.cancel()
        assertFalse(DesktopSleepTimer.afterTrack.value)
        assertNull(DesktopSleepTimer.minutes.value)
    }

    @Test
    fun aTimerOfNoLengthIsRefusedRatherThanFiringAtOnce() {
        assertFailsWith<IllegalArgumentException> { DesktopSleepTimer.start(0) }
        assertFailsWith<IllegalArgumentException> { DesktopSleepTimer.start(-5) }
    }

    @Test
    fun everyPresetIsAUsableDuration() {
        assertEquals(listOf(15, 30, 45, 60), DesktopSleepTimer.presets)
        DesktopSleepTimer.presets.forEach { DesktopSleepTimer.start(it) }
    }

    @Test
    fun theTimerExpiresAtItsDeadlineAndNotBefore() {
        useTestClock()
        DesktopSleepTimer.start(15)
        advanceMinutes(14)
        assertFalse(DesktopSleepTimer.isExpired())
        assertEquals(60_000L, DesktopSleepTimer.remainingMs())
        advanceMinutes(1)
        assertTrue(DesktopSleepTimer.isExpired())
    }

    @Test
    fun extendAddsToWhatIsLeft() {
        useTestClock()
        DesktopSleepTimer.start(30)
        advanceMinutes(10)
        DesktopSleepTimer.extend()
        assertEquals(35 * 60_000L, DesktopSleepTimer.remainingMs())
        assertEquals(45, DesktopSleepTimer.minutes.value)
    }

    @Test
    fun extendingEndOfTrackOrNothingStartsATimedTimer() {
        useTestClock()
        DesktopSleepTimer.startAfterTrack()
        DesktopSleepTimer.extend()
        assertFalse(DesktopSleepTimer.afterTrack.value)
        assertEquals(15 * 60_000L, DesktopSleepTimer.remainingMs())
        DesktopSleepTimer.cancel()
        DesktopSleepTimer.extend()
        assertEquals(15 * 60_000L, DesktopSleepTimer.remainingMs())
    }

    @Test
    fun endOfThisTrackPreparesNoBlendIntoTheNext() {
        // The bug: with Crossfade or Automix on, the next track was opened and
        // blended in, the current one never ended, and the timer that waits for
        // that end never paused anything.
        assertTrue(DesktopSleepTimer.preparesNext(repeatOne = false, afterTrack = false))
        assertFalse(DesktopSleepTimer.preparesNext(repeatOne = false, afterTrack = true))
        assertFalse(DesktopSleepTimer.preparesNext(repeatOne = true, afterTrack = false))
    }
}
