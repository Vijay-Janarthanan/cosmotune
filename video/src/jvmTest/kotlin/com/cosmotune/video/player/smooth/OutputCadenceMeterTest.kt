package com.cosmotune.video.player.smooth

import kotlin.test.*

class OutputCadenceMeterTest {
    @Test fun systematicOutputDropsLowerMeasuredFpsInsteadOfBeingDiscardedAsOutliers() {
        val meter = OutputCadenceMeter()
        val start = 10_000_000_000L
        var last = start
        for (i in 0 until 180) {
            last = start + i * 1_000_000_000L / 60
            if (i % 5 != 0) meter.tick(last, i.toLong(), i.toLong(), 1f)
        }
        assertTrue(assertNotNull(meter.fps(last)) in 47f..49f)
    }

    @Test fun aHeldFrameAndTheSameEndpointOfAdjacentPairsAreNotNewOutput() {
        val meter = OutputCadenceMeter()
        for (i in 0 until 60) meter.tick(10_000_000_000L + i * 16_666_667L, 10, 11, 1f)
        assertNull(meter.fps(11_000_000_000L))
        for (i in 0 until 60) meter.tick(11_000_000_000L + i * 16_666_667L, 11, 12, 0f)
        assertNull(meter.fps(12_000_000_000L))
    }

    @Test fun steadilyAdvancingInterpolatedPhasesMeasureTheActualCadence() {
        val meter = OutputCadenceMeter()
        var last = 0L
        for (i in 0 until 120) {
            last = 10_000_000_000L + i * 16_666_667L
            meter.tick(last, (i / 4).toLong(), (i / 4 + 1).toLong(), (i % 4) / 4f)
        }
        assertTrue(assertNotNull(meter.fps(last)) in 59.9f..60.1f)
        assertNull(meter.fps(last + 1_100_000_000L))
    }
}
