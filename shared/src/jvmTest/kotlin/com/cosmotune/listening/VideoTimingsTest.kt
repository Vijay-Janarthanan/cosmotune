package com.cosmotune.listening

import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VideoTimingsTest {
    @Test fun tapsMoveThePictureAndAreKeptPerVideo() {
        var timings = emptyMap<String, Long>()
        repeat(3) { timings = VideoTimings.nudge(timings, "vid1", VideoTimings.STEP_MS) }
        timings = VideoTimings.nudge(timings, "vid2", -VideoTimings.STEP_MS)
        assertEquals(mapOf("vid1" to 300L, "vid2" to -100L), timings)
        assertEquals(timings, VideoTimings.decode(VideoTimings.encode(timings)))
        // Back to zero forgets it; the limit holds.
        assertEquals(mapOf("vid1" to 300L), VideoTimings.nudge(timings, "vid2", VideoTimings.STEP_MS))
        assertEquals(5_000L, VideoTimings.set(timings, "vid1", 99_000)["vid1"])
        assertEquals(emptyMap(), VideoTimings.decode("vid\tnope\nother\t0"))
        assertEquals("Picture 0.3 s earlier", VideoTimings.label(300))
        assertEquals("Picture 0.1 s later", VideoTimings.label(-100))
        assertEquals("As matched", VideoTimings.label(0))
    }

    @Test fun theTimingShiftsEverySegmentOfTheMap() {
        val map = SyncMap(listOf(SyncSegment(0, 10_000, 2_000), SyncSegment(10_000, 20_000, 5_000)))
        val moved = VideoTimings.apply(map, 300)
        assertEquals(listOf(2_300L, 5_300L), moved.segments.map { it.offsetMs })
        assertSame(map, VideoTimings.apply(map, 0))
    }
}
