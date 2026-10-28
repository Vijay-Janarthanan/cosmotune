package com.cosmotune.canvas

import com.cosmotune.music.data.canvas.ForwardOnlySyncMap
import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ForwardOnlySyncMapTest {

    /** The measured map of "Thaen Thaen Thaen" that replayed 2.5 s and 3.1 s of footage. */
    private val thaen = SyncMap(listOf(
        SyncSegment(0, 137_500, -1711),
        SyncSegment(137_500, 167_500, -4181),
        SyncSegment(167_500, 215_000, -1708),
        SyncSegment(215_000, 238_600, -4800),
    ))

    private fun videoTimesNeverGoBack(map: SyncMap) {
        var last: Long? = null
        for (segment in map.segments) {
            val start = segment.songStartMs + segment.offsetAtMs(segment.songStartMs)
            val end = segment.songEndMs + segment.offsetAtMs(segment.songEndMs)
            if (last != null) assertTrue(start >= last - 150, "segment at ${segment.songStartMs} starts at video $start after reaching $last")
            last = maxOf(last ?: end, end)
        }
    }

    @Test fun aShortStepBackIsAWrongMatchAndTheOffsetCarriesOn() {
        val fixed = ForwardOnlySyncMap.of(thaen)
        videoTimesNeverGoBack(fixed)
        // everywhere the song lines up with the video at the offset the stretches either side agree on
        for (songMs in listOf(1_000L, 100_000L, 138_000L, 160_000L, 168_000L, 214_000L, 216_000L, 238_000L)) {
            val offset = fixed.offsetAt(songMs)
            assertTrue(offset != null && offset in -1_715L..-1_705L, "offset at $songMs was $offset")
        }
    }

    @Test fun aLongStepBackIsFootageAlreadyShownSoThePictureWaitsForNewFootage() {
        val replay = SyncMap(listOf(SyncSegment(0, 60_000, 0), SyncSegment(60_000, 120_000, -30_000)))
        val fixed = ForwardOnlySyncMap.of(replay)
        videoTimesNeverGoBack(fixed)
        assertEquals(0L, fixed.offsetAt(10_000))
        assertFalse(fixed.covers(70_000), "the 30 s of footage already shown is not shown again")
        assertEquals(-30_000L, fixed.offsetAt(100_000), "new footage starts when the video passes the 60 s it had reached")
    }

    @Test fun aLongStepBackThatNeverCatchesUpDropsTheStretch() {
        val replay = SyncMap(listOf(SyncSegment(0, 100_000, 0), SyncSegment(100_000, 120_000, -60_000)))
        val fixed = ForwardOnlySyncMap.of(replay)
        assertEquals(1, fixed.segments.size)
        assertNull(fixed.offsetAt(110_000))
    }

    @Test fun aMapThatOnlyMovesForwardIsReturnedAsItIs() {
        val edit = SyncMap(listOf(SyncSegment(0, 30_000, 4_000), SyncSegment(40_000, 180_000, 9_000)))
        assertSame(edit, ForwardOnlySyncMap.of(edit))
        val single = SyncMap.constant(2_000)
        assertSame(single, ForwardOnlySyncMap.of(single))
    }

    @Test fun aVideoPlayingSlowerThanTheSongIsNotMistakenForAStepBack() {
        // the measured map of "Appadipodu": the video runs at 0.9625 of the song's speed, 124 ms back at the join
        val slow = SyncMap(listOf(
            SyncSegment(0, 22_500, -406, rate = 0.9625),
            SyncSegment(22_500, 291_300, -1374, rate = 0.9625),
        ))
        assertSame(slow, ForwardOnlySyncMap.of(slow))
    }
}
