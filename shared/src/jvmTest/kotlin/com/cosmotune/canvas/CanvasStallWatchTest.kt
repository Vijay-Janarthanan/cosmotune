package com.cosmotune.canvas

import com.opencanvas.core.sync.CanvasStallWatch
import com.opencanvas.core.sync.CanvasSyncPolicy
import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A music video that stops while its song plays on is noticed, reopened at the mapped place, and given up on gently. */
class CanvasStallWatchTest {
    @Test fun noFrameForAMomentWhileOneIsDueIsAStall() {
        val watch = CanvasStallWatch()
        watch.repositioned(0L)
        assertFalse(watch.stalled(4_000L), "the first frame after opening has its settling time")
        watch.framePresented(4_000L)
        assertFalse(watch.stalled(5_400L))
        assertTrue(watch.stalled(5_500L), "1.5 s without a frame while the song plays")
        assertEquals(1_500L, watch.sinceLastFrameMs(5_500L))
    }

    @Test fun aPausedOrHiddenVideoNeverStalls() {
        val watch = CanvasStallWatch()
        watch.framePresented(0L)
        watch.idle()
        assertFalse(watch.stalled(60_000L))
        // Playing again: the next frame has the settling time, not the stall time.
        watch.repositioned(60_000L)
        assertFalse(watch.stalled(62_000L))
        assertTrue(watch.stalled(65_000L))
    }

    @Test fun failedReopensBackOffAndAFrameStartsOver() {
        val watch = CanvasStallWatch(maxReopens = 3, retryAfterMs = 5_000L)
        watch.framePresented(0L)
        repeat(2) { watch.reopenFailed(2_000L) }
        assertTrue(watch.mayReopen(2_000L), "tried again at once at first")
        watch.reopenFailed(2_000L)
        assertTrue(watch.givenUp, "the cover shows")
        assertFalse(watch.mayReopen(6_000L))
        assertTrue(watch.mayReopen(7_000L), "and it is tried again a while later")
        watch.reopenFailed(7_000L)
        assertFalse(watch.mayReopen(16_000L), "each failure waits longer")
        assertTrue(watch.mayReopen(17_000L))
        watch.framePresented(17_500L)
        assertFalse(watch.givenUp)
        assertEquals(0, watch.failures)
    }

    @Test fun theReopenedVideoStartsWhereTheMapPutsTheSong() {
        // The owner's map: one segment over the whole song, the video 78 ms behind and running 1 % faster.
        val policy = CanvasSyncPolicy(SyncMap(listOf(SyncSegment(0L, 277_800L, -78L, rate = 1.01))), 283_000L)
        assertEquals(0L, CanvasStallWatch.reopenAtMs(policy, 0L), "never before the start")
        assertEquals(100_922L, CanvasStallWatch.reopenAtMs(policy, 100_000L))
        assertEquals(201_922L, CanvasStallWatch.reopenAtMs(policy, 200_000L))
        // An edit: the second part of the song sits 12 s later in the video.
        val edited = CanvasSyncPolicy(SyncMap(listOf(SyncSegment(0L, 100_000L, 5_000L), SyncSegment(100_000L, 200_000L, 17_000L))), 230_000L)
        assertEquals(55_000L, CanvasStallWatch.reopenAtMs(edited, 50_000L))
        assertEquals(167_000L, CanvasStallWatch.reopenAtMs(edited, 150_000L))
    }

    @Test fun theReasonSaysWhatStoppedWithoutTheAddress() {
        assertEquals("the stream refused a range (HTTP 403)", CanvasStallWatch.reason("HTTP 403 for range at 1048576", streaming = true, expired = false))
        assertEquals("the stream refused a range (HTTP 403), its address has run out",
            CanvasStallWatch.reason("HTTP 403 for range at 0", streaming = true, expired = true))
        assertEquals("the stream stopped: failed to connect to <address>",
            CanvasStallWatch.reason("failed to connect to https://rr1.example/videoplayback?sig=x", streaming = true, expired = false))
        assertEquals("no bytes from the stream in time: a slow or stalled connection", CanvasStallWatch.reason(null, streaming = true, expired = false))
        assertEquals("the decoder gave no frame", CanvasStallWatch.reason(null, streaming = false, expired = false))
    }
}
