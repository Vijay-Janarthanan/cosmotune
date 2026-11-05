package com.cosmotune.manga.data

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Sliding window admission; network I/O runs after releasing the monitor. */
class RequestPacer(private val permits: Int, period: Long, unit: TimeUnit) {
    private val periodNanos = unit.toNanos(period)
    private val admitted = ArrayDeque<Long>()
    init { require(permits in 1..1000 && periodNanos in 1..TimeUnit.DAYS.toNanos(1)) }

    @Synchronized fun acquire(cancelled: () -> Boolean = { false }) {
        while (true) {
            if (cancelled()) throw IOException("Request cancelled while waiting for the source")
            val now = System.nanoTime()
            while (admitted.isNotEmpty() && now - admitted.first() >= periodNanos) admitted.removeFirst()
            if (admitted.size < permits) { admitted.addLast(now); return }
            val delay = minOf(periodNanos - (now - admitted.first()), TimeUnit.MILLISECONDS.toNanos(100)).coerceAtLeast(1)
            try { TimeUnit.NANOSECONDS.timedWait(this, delay) }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Source wait interrupted", error) }
        }
    }
}
