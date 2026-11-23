package com.cosmotune.music.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MixOverviewTest {
    private fun analysis(bpm: Double, key: String = "A minor") = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY, trackId = "t", duration = 200.0, bpm = bpm, key = key, keyConfidence = 0.8,
        introEndTime = 12.0, mixInTime = 8.0, mixOutTime = 185.0, outroStartTime = 180.0,
        energyCurve = listOf(EnergySample(0.0, 0.5), EnergySample(100.0, 2.0)),
    )

    @Test fun anAnalysedSongReadsAsTempoKeyAndMixPoints() {
        val view = MixOverview.of(analysis(123.6), analysis(126.0, "C major"))!!
        assertEquals("124 BPM", view.tempo)
        assertEquals("A minor", view.key)
        assertEquals(0.925f, view.fraction(view.mixOutS))
        assertEquals(listOf(0.25, 1.0), view.energy)
        assertEquals("The next song is at the same tempo in C major.", view.next)
        assertEquals("3:05", MixOverview.time(185.0))
    }

    @Test fun nothingWithoutAnAnalysis() {
        assertNull(MixOverview.of(null))
        assertNull(MixOverview.of(TrackAnalysis()))
        assertEquals("The next song is at a different tempo (150 BPM) in A minor.", MixOverview.of(analysis(120.0), analysis(150.0))!!.next)
    }
}
