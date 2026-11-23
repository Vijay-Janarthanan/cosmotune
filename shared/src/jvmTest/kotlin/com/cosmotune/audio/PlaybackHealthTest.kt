package com.cosmotune.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHealthTest {
    private val second = 1_000_000_000L

    /** Plays [seconds] of steady audio from [fromSec], the output running [ppm] away from the wall clock. */
    private fun PlaybackHealthMonitor.play(id: String, fromSec: Int, seconds: Int, ppm: Double = 0.0, startPos: Long = 0) {
        for (s in fromSec..fromSec + seconds) {
            val elapsed = (s - fromSec).toLong()
            observe(id, startPos + (elapsed * 1_000 * (1 + ppm / 1e6)).toLong(), true, false, 1f, s * second)
        }
    }

    @Test fun steadyPlayingMeasuresTheOutputClockAfterAMinute() {
        val monitor = PlaybackHealthMonitor()
        monitor.play("a", 0, 30, ppm = 200.0)
        assertNull(monitor.state.value.driftPpm)
        monitor.play("a", 30, 90, ppm = 200.0, startPos = 30_006)
        val drift = assertNotNull(monitor.state.value.driftPpm)
        assertTrue(drift in 150.0..250.0, "drift $drift")
        assertTrue(monitor.state.value.clockLabel.contains("fast"))
        assertEquals("Smooth, no stops to load", monitor.state.value.streamLabel)
    }

    @Test fun seeksAndPausesStartNewStretchesWithoutSkewingTheClock() {
        val monitor = PlaybackHealthMonitor()
        monitor.play("a", 0, 40)
        // A seek 90 s ahead, then a pause, then more steady playing.
        monitor.observe("a", 130_000, true, false, 1f, 41 * second)
        monitor.observe("a", 130_000, false, false, 1f, 60 * second)
        monitor.play("a", 80, 40, startPos = 130_000)
        val drift = assertNotNull(monitor.state.value.driftPpm)
        assertTrue(kotlin.math.abs(drift) < 1.0, "drift $drift")
        assertEquals("Keeps time with this device's clock", monitor.state.value.clockLabel)
        assertEquals(0, monitor.state.value.stalls)
    }

    @Test fun stopsToLoadAreCountedButNotTheStartOrTheWaitAfterASeek() {
        val monitor = PlaybackHealthMonitor()
        // The song starting: a wait before anything played is not a stop.
        monitor.observe("a", 0, true, true, 1f, 0)
        monitor.observe("a", 0, true, false, 1f, 2 * second)
        monitor.play("a", 2, 10)
        // A stop mid-song lasting 3 s.
        monitor.observe("a", 10_000, true, true, 1f, 13 * second)
        monitor.observe("a", 10_000, true, true, 1f, 16 * second)
        monitor.observe("a", 10_000, true, false, 1f, 16 * second)
        assertEquals(1, monitor.state.value.stalls)
        assertEquals(3_000, monitor.state.value.stalledMs)
        assertEquals("Stopped to load once, 3 s in all", monitor.state.value.streamLabel)
        // A seek that has to load is not a stop.
        monitor.observe("a", 100_000, true, true, 1f, 17 * second)
        monitor.observe("a", 100_000, true, false, 1f, 19 * second)
        assertEquals(1, monitor.state.value.stalls)
        // The desktop says when it seeks.
        monitor.observe("a", 101_000, true, false, 1f, 20 * second, seeks = 1)
        monitor.observe("a", 101_500, true, true, 1f, 20 * second + second / 2, seeks = 2)
        assertEquals(1, monitor.state.value.stalls)
        // A new song starts afresh.
        monitor.observe("b", 0, true, false, 1f, 30 * second, bufferedAheadMs = 38_000)
        assertEquals(0, monitor.state.value.stalls)
        assertEquals("38 s loaded ahead", monitor.state.value.bufferLabel)
    }

    @Test fun fidelityScoreNamesEveryLostPoint() {
        assertNull(FidelityScore.of(null, null, null, null, null))
        val perfect = FidelityScore.of(true, null, 96_000, 96_000, OutputFidelityFacts(mixing = OutputMixing.BIT_PERFECT_CONFIRMED))!!
        assertEquals(100, perfect.value); assertEquals("Excellent", perfect.label)
        val stream = FidelityScore.of(false, 160, 48_000, 44_100,
            OutputFidelityFacts(alteringStage = "equalizer", mixing = OutputMixing.SYSTEM_MIXER, systemName = "Android"))!!
        assertEquals(100 - 20 - 5 - 5 - 2, stream.value)
        assertEquals(
            listOf("Lossy stream at 160 kbps", "Equalizer changes the sound", "Resampled from 48 kHz to 44.1 kHz", "Android's mixer may still change it"),
            stream.deductions.map { it.reason },
        )
        assertEquals("Fair", stream.label)
        assertEquals(98, FidelityScore.of(true, null, 44_100, null, OutputFidelityFacts())!!.value)
    }
}
