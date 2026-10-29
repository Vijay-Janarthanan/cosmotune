package com.cosmotune.video.player.smooth

/**
 * Measures a frame rate from the times frames arrive: the median of the last [window] gaps, so one late frame, a hiccup or a
 * burst after a stall does not move it. The caller supplies the clock (nanoseconds), which keeps this a pure class.
 *
 * [fps] is null until [MIN_GAPS] gaps are known, and again once the newest frame is older than [staleNanos] (paused, stopped or
 * a seek in progress): an unknown rate is never guessed. A gap longer than [resetGapNanos] starts the measure again, since
 * what comes after a long stall is a different stretch of playback.
 */
class FpsMeter(
    private val window: Int = 24,
    private val staleNanos: Long = 1_500_000_000L,
    private val resetGapNanos: Long = 1_000_000_000L,
) {
    private val gaps = LongArray(window)
    private val sorted = LongArray(window)
    private var count = 0
    private var next = 0
    private var last = NONE

    /** Records a frame at [nowNanos]. A frame at the same instant as the last one (a redraw of the same picture) is ignored. */
    @Synchronized
    fun tick(nowNanos: Long) {
        if (last != NONE) {
            val gap = nowNanos - last
            if (gap <= MIN_GAP_NANOS) return
            if (gap > resetGapNanos) {
                count = 0
                next = 0
            } else {
                gaps[next] = gap
                next = (next + 1) % window
                if (count < window) count++
            }
        }
        last = nowNanos
    }

    /**
     * The typical gap in nanoseconds, or null while unknown: the mean of the gaps within 40 % of the median, so that a gap lost to
     * a dropped frame or a burst does not count, and the wobble of the arrival times averages out.
     */
    @Synchronized
    fun intervalNanos(nowNanos: Long): Long? {
        if (count < MIN_GAPS || last == NONE || nowNanos - last > staleNanos) return null
        System.arraycopy(gaps, 0, sorted, 0, count)
        java.util.Arrays.sort(sorted, 0, count)
        val median = if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2
        var sum = 0L
        var used = 0
        for (i in 0 until count) {
            val gap = gaps[i]
            if (gap * 10 >= median * 6 && gap * 10 <= median * 14) {
                sum += gap
                used++
            }
        }
        return if (used > 0) sum / used else median
    }

    /** The frame rate, or null while unknown. */
    fun fps(nowNanos: Long): Float? = intervalNanos(nowNanos)?.let { 1_000_000_000.0f / it }

    /** Forgets everything (a new video, a changed size). */
    @Synchronized
    fun reset() {
        count = 0
        next = 0
        last = NONE
    }

    companion object {
        /** How many gaps make a measure (about half a second of a 24 fps video). */
        const val MIN_GAPS = 8

        /** Frames closer than this are one frame (a window redraw), not a 1000 fps video. */
        private const val MIN_GAP_NANOS = 1_000_000L

        private const val NONE = Long.MIN_VALUE
    }
}
