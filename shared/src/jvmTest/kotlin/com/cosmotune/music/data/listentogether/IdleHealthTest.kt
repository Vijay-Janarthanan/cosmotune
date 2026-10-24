package com.cosmotune.music.data.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The idle Listen Together health poll shared by the phone and the desktop. */
class IdleHealthTest {
    // The policy cases follow upstream's ListenTogetherIdleHealthTest (PR #820) for the same rule.
    @Test fun aClosedScreenNeverProbes() {
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(false, false, false, null))
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(false, false, true, false))
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(false, true, true, false))
    }

    @Test fun aLiveSocketNeverProbesAndMeteredWithoutAPartySkips() {
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(true, true, true, false))
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(true, true, false, null))
        assertTrue(IdleHealthPolicy.shouldSkipIdleProbe(true, false, false, true))
    }

    @Test fun anOpenScreenProbesOnWifiOrUnknownAndInAPartyEvenWhenMetered() {
        assertFalse(IdleHealthPolicy.shouldSkipIdleProbe(true, false, false, false))
        assertFalse(IdleHealthPolicy.shouldSkipIdleProbe(true, false, false, null))
        assertFalse(IdleHealthPolicy.shouldSkipIdleProbe(true, false, true, true))
    }

    @Test fun theDebounceIsShorterThanThePollAndAForcedProbeIsNeverDebounced() {
        assertTrue(IdleHealthPolicy.MIN_GAP_MS <= IdleHealthPolicy.POLL_INTERVAL_MS)
        assertFalse(IdleHealthPolicy.debounced(nowMs = 100, lastProbeMs = null, force = false))
        assertTrue(IdleHealthPolicy.debounced(nowMs = 4_000, lastProbeMs = 0, force = false))
        assertFalse(IdleHealthPolicy.debounced(nowMs = 5_000, lastProbeMs = 0, force = false))
        assertFalse(IdleHealthPolicy.debounced(nowMs = 1, lastProbeMs = 0, force = true))
        // Shortly after boot a monotonic clock is small; the first probe is still allowed.
        assertFalse(IdleHealthPolicy.debounced(nowMs = 1_000, lastProbeMs = null, force = false))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun stop() = scope.cancel()

    private val clock = AtomicLong(1_000)
    private val probes = AtomicInteger()
    @Volatile private var state = IdleHealthMonitor.State(connectionLive = false, inParty = false, metered = false)
    private fun monitor() = IdleHealthMonitor(scope, clock::get, { state }, { probes.incrementAndGet() }, pollIntervalMs = 30, minGapMs = 5_000)

    private fun waitFor(ms: Long = 2_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(5) }
        return condition()
    }

    @Test fun openingProbesOnceThenThePollProbesOnlyOutsideTheDebounceAndClosingStopsIt() {
        val monitor = monitor()
        Thread.sleep(150)
        assertEquals(0, probes.get(), "nothing is asked while the screen is closed")
        monitor.setScreenActive(true)
        assertTrue(waitFor { probes.get() == 1 })
        Thread.sleep(150) // several 30 ms ticks, all inside the 5 s debounce of the fake clock
        assertEquals(1, probes.get())
        clock.addAndGet(5_000)
        assertTrue(waitFor { probes.get() == 2 })
        monitor.setScreenActive(false)
        clock.addAndGet(60_000)
        Thread.sleep(150)
        assertEquals(2, probes.get())
    }

    @Test fun aLiveSocketOrAMeteredIdleConnectionAsksNothingUntilThatChanges() {
        state = IdleHealthMonitor.State(connectionLive = true, inParty = true, metered = false)
        val monitor = monitor()
        monitor.setScreenActive(true)
        Thread.sleep(150)
        state = IdleHealthMonitor.State(connectionLive = false, inParty = false, metered = true)
        Thread.sleep(150)
        assertEquals(0, probes.get())
        // In a party whose socket dropped, metered data is spent to find the server again.
        state = IdleHealthMonitor.State(connectionLive = false, inParty = true, metered = true)
        assertTrue(waitFor { probes.get() == 1 })
        monitor.setScreenActive(false)
    }

    @Test fun aProbeMadeElsewhereIsNotRepeatedWhenTheScreenOpens() {
        val monitor = monitor()
        monitor.noteProbe()
        monitor.setScreenActive(true)
        Thread.sleep(150)
        assertEquals(0, probes.get())
        monitor.setScreenActive(false)
    }
}
