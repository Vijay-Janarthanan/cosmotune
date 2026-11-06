package com.cosmotune.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sleep timer's deadline and the wait that runs it out.
 *
 * The bug this guards: a 30 minute timer, set with the screen off and one song
 * on repeat, was still playing two hours later. The wait was one coroutine
 * delay of the whole remainder, and a delay counts uptime, which stands still
 * while the CPU sleeps between audio buffer refills. The deadline itself was
 * always right; the wait never looked at it again.
 */
class SleepTimerTest {

    /** elapsedRealtime as the test sees it. */
    private var realtime = 1_000_000L

    @Before
    fun setUp() {
        SleepTimer.clock = { realtime }
        SleepTimer.cancel()
    }

    @After
    fun tearDown() {
        SleepTimer.cancel()
        SleepTimer.clock = { android.os.SystemClock.elapsedRealtime() }
    }

    /**
     * A phone whose CPU is awake a quarter of the time: each millisecond of
     * uptime a delay waits for is four of real time.
     */
    private val sleepyWait: suspend (Long) -> Unit = { uptimeMs -> realtime += uptimeMs * 4 }

    @Test
    fun theWaitEndsAtTheDeadlineEvenWhenTheCpuSleepsMostOfTheTime() = runBlocking {
        SleepTimer.start(30)
        val deadline = SleepTimer.deadline.value!!
        SleepTimer.awaitDeadline(deadline, sleepyWait)
        val late = realtime - deadline
        // One step of uptime late at most, here four steps of real time: 20
        // seconds. The single delay it replaces ran out after two hours.
        assertTrue("ran out ${late}ms after the deadline", late in 0..SleepTimer.CHECK_EVERY_MS * 4)
        assertTrue(SleepTimer.isDue())
    }

    @Test
    fun theOldSingleDelayIsWhatOverran() = runBlocking {
        // The shape of the code that was replaced, against the same sleepy
        // clock, so the test above is known to be measuring the right thing.
        SleepTimer.start(30)
        val deadline = SleepTimer.deadline.value!!
        sleepyWait(deadline - realtime)
        assertEquals(90 * 60_000L, realtime - deadline)
    }

    @Test
    fun aWaitOnAnAwakePhoneEndsExactlyOnTime() = runBlocking {
        SleepTimer.start(15)
        val deadline = SleepTimer.deadline.value!!
        val waits = mutableListOf<Long>()
        SleepTimer.awaitDeadline(deadline) { realtime += it; waits += it }
        assertEquals(deadline, realtime)
        assertTrue("no single wait is longer than a step", waits.all { it <= SleepTimer.CHECK_EVERY_MS })
    }

    @Test
    fun aDeadlineAlreadyPassedReturnsAtOnce() = runBlocking {
        SleepTimer.start(15)
        val deadline = SleepTimer.deadline.value!!
        realtime = deadline + 60_000L
        var waited = false
        SleepTimer.awaitDeadline(deadline) { waited = true }
        assertFalse(waited)
        assertTrue(SleepTimer.isDue())
    }

    @Test
    fun theCountdownIsTheDeadlineLessTheClock() {
        SleepTimer.start(30)
        assertEquals(30 * 60_000L, SleepTimer.remainingMs())
        realtime += 6 * 60_000L + 19_000L
        assertEquals(23 * 60_000L + 41_000L, SleepTimer.remainingMs())
        assertFalse(SleepTimer.isDue())
        realtime += 24 * 60_000L
        assertEquals(0L, SleepTimer.remainingMs())
        assertTrue(SleepTimer.isDue())
    }

    @Test
    fun extendAddsToWhatIsLeft() {
        SleepTimer.start(30)
        realtime += 10 * 60_000L
        SleepTimer.extend()
        assertEquals(35 * 60_000L, SleepTimer.remainingMs())
        assertEquals(45, SleepTimer.minutes.value)
    }

    @Test
    fun extendingAnOverdueTimerGivesTheWholeExtension() {
        SleepTimer.start(15)
        realtime += 20 * 60_000L
        SleepTimer.extend()
        assertEquals(15 * 60_000L, SleepTimer.remainingMs())
    }

    @Test
    fun extendingAfterThisSongMakesItATimedTimer() {
        SleepTimer.startAfterTrack()
        SleepTimer.extend()
        assertFalse(SleepTimer.afterTrack.value)
        assertEquals(15 * 60_000L, SleepTimer.remainingMs())
    }

    @Test
    fun theTwoModesDisplaceEachOther() {
        SleepTimer.start(15)
        SleepTimer.startAfterTrack()
        assertTrue(SleepTimer.afterTrack.value)
        assertNull(SleepTimer.deadline.value)
        assertFalse(SleepTimer.isDue())
        SleepTimer.start(30)
        assertFalse(SleepTimer.afterTrack.value)
        assertTrue(SleepTimer.isRunning)
        SleepTimer.cancel()
        assertFalse(SleepTimer.isRunning)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTimerOfNoLengthIsRefusedRatherThanFiringAtOnce() {
        SleepTimer.start(0)
    }
}
