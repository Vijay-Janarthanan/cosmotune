package com.cosmotune.design

import kotlin.test.*

class NowPlayingVideoMetricsTest {
    @Test fun fourByFiveKeepsBothPortraitAxesAndFitsTheAvailablePane() {
        for ((w, h) in listOf(390f to 844f, 400f to 300f, 840f to 400f, 1200f to 800f)) {
            val frame = NowPlayingVideoMetrics.fit(w, h)
            assertEquals(0.8f, frame.width / frame.height, 0.0001f)
            assertTrue(frame.x >= 0 && frame.y >= 0)
            assertTrue(frame.x + frame.width <= w && frame.y + frame.height <= h)
        }
    }

    @Test fun phoneVideoLeavesTheTopBarCutoutAndExpandedTransportVisible() {
        for ((w, h, controls) in listOf(Triple(390f, 844f, 300f), Triple(360f, 640f, 260f), Triple(320f, 480f, 240f), Triple(600f, 1100f, 460f))) {
            val frame = NowPlayingVideoMetrics.portrait(w, h, controls, 28f)
            assertEquals(0.8f, frame.width / frame.height, 0.0001f)
            assertTrue(frame.y >= 28f + NowPlayingVideoMetrics.PHONE_HEADER_DP)
            assertTrue(frame.y + frame.height <= h - controls - NowPlayingVideoMetrics.CONTROLS_HANDLE_DP - NowPlayingVideoMetrics.CONTROLS_GAP_DP + 0.001f)
            assertTrue(frame.height <= h * 0.60f)
            assertTrue(frame.x >= NowPlayingVideoMetrics.PHONE_SIDE_DP)
        }
    }

    @Test fun anOversizedTextStackGetsPriorityOverVideoInsteadOfAnOverlap() {
        val frame = NowPlayingVideoMetrics.portrait(320f, 480f, 600f, 30f)
        assertEquals(0f, frame.width)
        assertEquals(0f, frame.height)
        assertTrue(frame.y in 0f..480f)
    }

    @Test fun invalidAndEmptyPanesNeverProduceInvalidDimensions() {
        for ((w, h) in listOf(0f to 400f, -1f to -5f, Float.NaN to 40f, 50f to Float.POSITIVE_INFINITY)) {
            val frame = NowPlayingVideoMetrics.fit(w, h)
            assertTrue(frame.width.isFinite() && frame.height.isFinite())
            assertEquals(0f, frame.width)
            assertEquals(0f, frame.height)
        }
    }
}
