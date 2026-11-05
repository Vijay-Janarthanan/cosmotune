package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.LyricLine

/**
 * Which lines of a song are being sung at a moment, for songs whose voices overlap (the call and the answer of a duet): the
 * old panel's `activeLyricRows` (`LyricFocus.kt`) without the list it builds on every frame. A line is active when it is the
 * latest whose stamp has passed, or when it has a known end (words, a stated end, or an answering voice with words) that has
 * not been reached. A break is never kept active by its own end.
 *
 * Built once per list of lines; every question after that is a few comparisons and allocates nothing, so it can be asked on
 * every frame.
 */
class LyricActivity(lines: List<LyricLine>) {
    private val size = lines.size
    private val times = LongArray(size)

    /** When the line stops being sung, for a line whose end is known; [NO_END] for every other. */
    private val ends = LongArray(size)

    /** The largest of [ends] up to and including each index: when it is behind the playhead, nothing at or above that index is still being sung. */
    private val reach = LongArray(size)

    /** The earliest stamp among the lines after each index: while the playhead is before it, no later line has begun. */
    private val laterStamp = LongArray(size)

    init {
        var best = NO_END
        for (i in 0 until size) {
            val line = lines[i]
            times[i] = line.timeMs
            ends[i] = if (!line.isGap && (line.hasKnownEnd || line.background?.hasKnownEnd == true)) line.endMs else NO_END
            if (ends[i] > best) best = ends[i]
            reach[i] = best
        }
        var earliest = Long.MAX_VALUE
        for (i in size - 1 downTo 0) {
            laterStamp[i] = earliest
            if (times[i] < earliest) earliest = times[i]
        }
    }

    /**
     * Whether line [index] can still be being sung once a later line has begun: it has a known end, and that end is after the
     * earliest stamp of a line that follows it. A line that cannot is active exactly while it is the latest, which a row can find
     * out from the current line alone, without asking the clock.
     */
    fun mayOverlap(index: Int): Boolean = index in 0 until size && ends[index] != NO_END && ends[index] > laterStamp[index]

    /** The last line whose stamp has passed, or -1 before the first. */
    fun latest(positionMs: Long): Int {
        var i = size - 1
        while (i >= 0) {
            if (times[i] <= positionMs) return i
            i--
        }
        return -1
    }

    /** Whether line [index] is being sung at [positionMs], given the [latest] line at that moment. */
    fun isActive(index: Int, latest: Int, positionMs: Long): Boolean {
        if (index < 0 || index >= size) return false
        return index == latest || (times[index] <= positionMs && positionMs < ends[index])
    }

    /** The uppermost line being sung: the one that owns the scroll anchor until it ends. -1 when nothing has been sung yet. */
    fun firstActive(latest: Int, positionMs: Long): Int {
        if (latest < 0) return -1
        var first = latest
        var i = latest - 1
        while (i >= 0 && reach[i] > positionMs) {
            if (isActive(i, latest, positionMs)) first = i
            i--
        }
        return first
    }

    private companion object {
        const val NO_END = Long.MIN_VALUE
    }
}
