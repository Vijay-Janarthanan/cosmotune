package com.cosmotune.video.segments

import kotlin.jvm.Synchronized

/**
 * A client-side request limit with a sliding window per minute and per day (spec 7.3: Return YouTube Dislike allows at
 * most 100 requests a minute and 10,000 a day per client, so the defaults are that rule). [tryAcquire] says whether one
 * more request may be sent now and, if so, counts it; a refused request is not counted. [clock] is milliseconds; one that
 * goes back is treated as standing still, so a clock change cannot let a burst through.
 */
class RateLimiter(
    private val perMinute: Int = 100,
    private val perDay: Int = 10_000,
    private val clock: () -> Long,
) {
    init {
        require(perMinute > 0) { "perMinute must be positive" }
        require(perDay > 0) { "perDay must be positive" }
    }

    private val minute = ArrayDeque<Long>()
    private val day = ArrayDeque<Long>()
    private var latest = Long.MIN_VALUE

    /** Counts a request and returns true when both windows have room; false (and counts nothing) otherwise. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = maxOf(clock(), latest)
        latest = now
        evict(minute, now - MINUTE_MS)
        evict(day, now - DAY_MS)
        if (minute.size >= perMinute || day.size >= perDay) return false
        minute.addLast(now)
        day.addLast(now)
        return true
    }

    private fun evict(window: ArrayDeque<Long>, cutoff: Long) {
        while (window.isNotEmpty() && window.first() <= cutoff) window.removeFirst()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
