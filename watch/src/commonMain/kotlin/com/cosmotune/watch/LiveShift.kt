package com.cosmotune.watch

/**
 * Pausing and rewinding live TV: the engines keep a window of what a live channel has sent since it was opened (the HLS
 * playlist's own window, or what the computer's player has kept), from 0 to [windowMs], the playhead inside it.
 */
object LiveShift {
    /** Within this of the window's end is live: the players stay a little behind the newest picture on their own. */
    const val EDGE_MS = 25_000L
    /** A window shorter than this is not worth rewinding in. */
    const val MIN_WINDOW_MS = 45_000L

    fun canShift(windowMs: Long): Boolean = windowMs >= MIN_WINDOW_MS

    /** How far behind the live picture the playhead is. */
    fun behindMs(windowMs: Long, positionMs: Long): Long = (windowMs - positionMs).coerceAtLeast(0)

    fun isBehind(windowMs: Long, positionMs: Long): Boolean = canShift(windowMs) && behindMs(windowMs, positionMs) > EDGE_MS

    /** "● Live", or "Behind live by 2:10". */
    fun label(windowMs: Long, positionMs: Long): String =
        if (!isBehind(windowMs, positionMs)) "● Live" else "Behind live by " + clock(behindMs(windowMs, positionMs))

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }
}
