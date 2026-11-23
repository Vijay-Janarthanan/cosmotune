package com.cosmotune.listening

import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicSkipsTest {
    private fun segment(uuid: String, start: Long, end: Long, category: SegmentCategory = SegmentCategory.MUSIC_OFFTOPIC) =
        SkipSegment(uuid, category, SegmentAction.SKIP, start, end, null, 5, false, null)

    @Test fun eachMarkedPartIsSkippedOnceAndOnlyWhenWorthIt() {
        val parts = listOf(segment("intro", 0, 20_000), segment("short", 60_000, 61_000), segment("talk", 200_000, 230_000, SegmentCategory.FILLER))
        assertEquals("intro", MusicSkips.target(500, parts, emptySet())?.uuid)
        assertNull(MusicSkips.target(500, parts, setOf("intro")), "skipped once")
        assertNull(MusicSkips.target(19_500, parts, emptySet()), "nearly over")
        assertNull(MusicSkips.target(60_200, parts, emptySet()), "too short")
        assertNull(MusicSkips.target(210_000, parts, emptySet()), "filler is not ours to skip")
        assertEquals("Skipped a part without music", MusicSkips.message(parts[0]))
    }

    @Test fun theSkipperFetchesOncePerSongAndForgetsAtTheNext() = runBlocking {
        var fetched = 0
        val skipper = MusicSkipper(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> fetched++; listOf(segment("intro", 0, 20_000)) }
        assertEquals("intro", skipper.tick("a", 1_000, 240_000)?.uuid)
        assertNull(skipper.tick("a", 2_000, 240_000))
        assertEquals("intro", skipper.tick("b", 1_000, 240_000)?.uuid)
        assertEquals(2, fetched)
    }
}
