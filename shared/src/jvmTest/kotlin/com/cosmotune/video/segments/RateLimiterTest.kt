package com.cosmotune.video.segments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client-side request limit of Return YouTube Dislike (spec 7.3): 100 a minute and 10,000 a day. */
class RateLimiterTest {

    @Test
    fun theDefaultsAreTheServicesRule() {
        var now = 0L
        val limiter = RateLimiter { now }
        var granted = 0
        repeat(150) { if (limiter.tryAcquire()) granted++ }
        assertEquals(100, granted, "100 a minute")
        now += 60_000
        granted = 0
        repeat(150) { if (limiter.tryAcquire()) granted++ }
        assertEquals(100, granted, "a minute later another 100")
    }

    @Test
    fun theMinuteWindowSlides() {
        var now = 0L
        val limiter = RateLimiter(perMinute = 3, perDay = 1000) { now }
        assertTrue(limiter.tryAcquire())          // t = 0
        now = 20_000
        assertTrue(limiter.tryAcquire())          // t = 20 s
        now = 40_000
        assertTrue(limiter.tryAcquire())          // t = 40 s
        now = 59_999
        assertFalse(limiter.tryAcquire(), "three in the last minute")
        now = 60_000
        assertTrue(limiter.tryAcquire(), "the first one has left the window")
        assertFalse(limiter.tryAcquire())
        now = 80_000
        assertTrue(limiter.tryAcquire(), "the second one has left too")
    }

    @Test
    fun aRefusedRequestDoesNotUseUpTheLimit() {
        var now = 0L
        val limiter = RateLimiter(perMinute = 1, perDay = 1000) { now }
        assertTrue(limiter.tryAcquire())
        repeat(50) { assertFalse(limiter.tryAcquire()) }
        now = 60_000
        assertTrue(limiter.tryAcquire(), "the refusals were not counted")
    }

    @Test
    fun theDayLimitHoldsEvenWhenEveryMinuteIsFree() {
        var now = 0L
        val limiter = RateLimiter(perMinute = 100, perDay = 250) { now }
        var granted = 0
        repeat(10) {
            repeat(100) { if (limiter.tryAcquire()) granted++ }
            now += 60_000
        }
        assertEquals(250, granted)
        now = 24 * 60 * 60 * 1000L - 1
        assertFalse(limiter.tryAcquire(), "still within the day of the first request")
        now = 24 * 60 * 60 * 1000L + 60_000
        assertTrue(limiter.tryAcquire(), "the day has passed")
    }

    @Test
    fun aClockThatGoesBackDoesNotLetRequestsThrough() {
        var now = 100_000L
        val limiter = RateLimiter(perMinute = 2, perDay = 100) { now }
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        now = 50_000
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun theLimitsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { RateLimiter(perMinute = 0, perDay = 10) { 0L } }
        assertFailsWith<IllegalArgumentException> { RateLimiter(perMinute = 10, perDay = 0) { 0L } }
    }
}

/** The small in-memory cache of the three clients: entries live for the time to live, and the oldest go first. */
class TtlCacheTest {

    @Test
    fun anEntryLivesForItsTimeToLiveAndNoLonger() {
        var now = 1_000L
        val cache = TtlCache<String, Int>(ttlMs = 100, maxEntries = 10) { now }
        cache.put("a", 1)
        assertEquals(1, cache.get("a"))
        now = 1_099
        assertEquals(1, cache.get("a"))
        now = 1_100
        assertNull(cache.get("a"), "expired at exactly the time to live")
        assertEquals(0, cache.size, "an expired entry is dropped when it is read")
    }

    @Test
    fun aMissIsNull() {
        val cache = TtlCache<String, Int>(100, 10) { 0L }
        assertNull(cache.get("nothing"))
    }

    @Test
    fun puttingAgainReplacesTheValueAndRestartsTheClock() {
        var now = 0L
        val cache = TtlCache<String, String>(100, 10) { now }
        cache.put("k", "old")
        now = 90
        cache.put("k", "new")
        now = 150
        assertEquals("new", cache.get("k"), "60 ms old, not 150")
        assertEquals(1, cache.size)
    }

    @Test
    fun theOldestEntryGoesWhenTheCacheIsFull() {
        var now = 0L
        val cache = TtlCache<Int, Int>(10_000, 3) { now }
        for (i in 1..3) { cache.put(i, i * 10); now++ }
        cache.put(4, 40)
        assertNull(cache.get(1))
        assertEquals(20, cache.get(2))
        assertEquals(30, cache.get(3))
        assertEquals(40, cache.get(4))
        assertEquals(3, cache.size)
    }

    @Test
    fun aReplacedEntryCountsAsTheNewest() {
        var now = 0L
        val cache = TtlCache<Int, Int>(10_000, 3) { now }
        for (i in 1..3) { cache.put(i, i); now++ }
        cache.put(1, 100)   // 1 is now the newest, 2 the oldest
        cache.put(4, 4)
        assertNull(cache.get(2))
        assertEquals(100, cache.get(1))
    }

    @Test
    fun expiredEntriesAreDroppedBeforeLiveOnesWhenMakingRoom() {
        var now = 0L
        val cache = TtlCache<Int, Int>(100, 3) { now }
        cache.put(1, 1)        // t = 0
        now = 60
        cache.put(2, 2)        // t = 60
        cache.put(3, 3)
        now = 120              // entry 1 has expired
        cache.put(4, 4)
        assertEquals(2, cache.get(2), "the live entries stay")
        assertEquals(3, cache.get(3))
        assertEquals(4, cache.get(4))
        assertNull(cache.get(1))
    }

    @Test
    fun removeAndClear() {
        val cache = TtlCache<String, Int>(100, 10) { 0L }
        cache.put("a", 1)
        cache.put("b", 2)
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun theBoundsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { TtlCache<String, Int>(0, 10) { 0L } }
        assertFailsWith<IllegalArgumentException> { TtlCache<String, Int>(10, 0) { 0L } }
    }
}
