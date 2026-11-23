package com.cosmotune.music.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreshShuffleTest {
    private val hour = 3_600_000L
    private val now = 1_000L * hour

    @Test fun songsPlayedLatelyTendToComeLast() {
        val ids = (0 until 20).map { "s$it" }
        val played = mapOf("s0" to now - hour / 2, "s1" to now - hour)
        var lateSum = 0
        repeat(200) { seed ->
            val order = FreshShuffle.order(ids, Random(seed), now) { played[it] }
            lateSum += order.indexOf("s0") + order.indexOf("s1")
            assertEquals(ids.toSet(), order.toSet())
        }
        // Played within the hour, they average well past the middle (9.5 each) of twenty.
        assertTrue(lateSum / 400.0 > 13.0, "average place ${lateSum / 400.0}")
    }

    @Test fun withoutHistoryItIsAPlainShuffle() {
        val ids = (0 until 10).map { "s$it" }
        assertEquals(ids.toSet(), FreshShuffle.order(ids, Random(1), now) { null }.toSet())
        assertEquals(1.0, FreshShuffle.weight(null, now))
        assertEquals(1.0, FreshShuffle.weight(now - 30 * 24 * hour, now))
        assertTrue(FreshShuffle.weight(now - hour, now) < 0.1)
    }

    @Test fun theLastPlayedTimesAreKeptAndBounded() {
        var map = emptyMap<String, Long>()
        repeat(FreshShuffle.REMEMBERED + 5) { map = FreshShuffle.played(map, "s$it", it.toLong()) }
        assertEquals(FreshShuffle.REMEMBERED, map.size)
        assertEquals(map, FreshShuffle.decode(FreshShuffle.encode(map)))
        assertEquals(null, map["s0"])
    }
}
