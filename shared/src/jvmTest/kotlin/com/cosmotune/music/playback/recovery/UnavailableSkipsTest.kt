package com.cosmotune.music.playback.recovery

import com.cosmotune.music.data.innertube.StreamResolver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnavailableSkipsTest {
    @Test fun aSongNobodyCanPlayIsSkipped() {
        val skips = UnavailableSkips(limit = 3)
        assertTrue(skips.shouldSkip("a"))
        assertTrue(skips.shouldSkip("b"))
    }

    @Test fun theSameSongComingRoundAgainStopsInsteadOfSpinning() {
        // Repeat-one on a dead song, or a queue of one.
        val skips = UnavailableSkips(limit = 3)
        assertTrue(skips.shouldSkip("a"))
        assertFalse(skips.shouldSkip("a"))
        // Stopping starts the count again.
        assertTrue(skips.shouldSkip("a"))
    }

    @Test fun aRunOfDeadSongsStopsAtTheLimit() {
        val skips = UnavailableSkips(limit = 3)
        assertTrue(skips.shouldSkip("a"))
        assertTrue(skips.shouldSkip("b"))
        assertTrue(skips.shouldSkip("c"))
        assertFalse(skips.shouldSkip("d"))
    }

    @Test fun aSongThatPlaysEndsTheRun() {
        val skips = UnavailableSkips(limit = 2)
        assertTrue(skips.shouldSkip("a"))
        skips.played()
        assertTrue(skips.shouldSkip("a"))
        assertTrue(skips.shouldSkip("b"))
    }

    @Test fun onlyAVerdictCounts() {
        val verdict = StreamResolver.PermanentlyUnplayableException("This song's upload is no longer on YouTube")
        assertTrue(UnavailableSkips.isUnavailable(verdict))
        assertTrue(UnavailableSkips.isUnavailable(IllegalStateException("wrapped", verdict)))
        assertFalse(UnavailableSkips.isUnavailable(IllegalStateException("No enabled music source could play this track")))
        assertFalse(UnavailableSkips.isUnavailable(null))
    }
}
