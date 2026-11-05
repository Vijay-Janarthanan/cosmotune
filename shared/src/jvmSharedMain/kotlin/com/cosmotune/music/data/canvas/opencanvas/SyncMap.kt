package com.opencanvas.core.sync

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * One stretch of a song that maps onto its music video with a single constant offset:
 * for `songStartMs <= songTime < songEndMs`, `videoTime = songTime + offsetMs`.
 *
 * A video whose music runs at another speed than the song's (film videos are often 25/24 faster or
 * slower than the album track) has a [rate] other than 1: the video advances [rate] seconds per song
 * second, and [offsetMs] is `videoTime - songTime` at [songStartMs]; at any song time inside,
 * `videoTime = songTime + offsetMs + (rate - 1) * (songTime - songStartMs)`.
 *
 * @property ncc Mean normalised cross-correlation of the matched audio in `0..1`, a diagnostic of how
 *   well the segment matches; `0.0` when it was not measured.
 */
@Serializable
data class SyncSegment(
    val songStartMs: Long,
    val songEndMs: Long,
    val offsetMs: Long,
    val ncc: Double = 0.0,
    val rate: Double = 1.0,
) {
    init {
        require(songEndMs > songStartMs) { "segment must have a positive length: $songStartMs..$songEndMs" }
        require(rate > 0.5 && rate < 2.0) { "rate out of range: $rate" }
    }

    /** `videoTime - songTime` at [songMs] (extrapolated, whether or not it lies inside the segment). */
    fun offsetAtMs(songMs: Long): Long =
        if (rate == 1.0) offsetMs else offsetMs + ((rate - 1.0) * (songMs - songStartMs)).roundToLong()

    operator fun contains(songMs: Long): Boolean = songMs >= songStartMs && songMs < songEndMs
}

/**
 * How a song's timeline maps onto its music video's.
 *
 * A music video is usually an *edit* of the song: it has an intro, may repeat or drop bars and adds
 * an outro, so one constant offset is only right for part of the song. A map is a list of segments,
 * each with its own offset; song time outside every segment has no matching picture and a player
 * shows the still artwork there.
 *
 * Segments are sorted by start and must not overlap; overlapping or empty input is trimmed rather
 * than rejected, since the map comes from measured data.
 */
class SyncMap(segments: List<SyncSegment>) {

    /** The segments in song order, without overlaps. */
    val segments: List<SyncSegment> = normalise(segments)

    /** True when no part of the song maps onto the video. */
    val isEmpty: Boolean get() = segments.isEmpty()

    /**
     * The offset of the longest segment: what a client that can only use one number should use.
     * Zero for an empty map.
     */
    val primaryOffsetMs: Long
        get() = segments.maxByOrNull { it.songEndMs - it.songStartMs }?.offsetMs ?: 0L

    /** The offset in force at [songMs], or null where the song has no matching picture. */
    fun offsetAt(songMs: Long): Long? {
        // Few segments (a handful at most): a linear scan is as fast as a binary search.
        for (segment in segments) {
            if (songMs < segment.songStartMs) return null
            if (songMs < segment.songEndMs) return segment.offsetAtMs(songMs)
        }
        return null
    }

    /** How many video seconds pass per song second at [songMs] (1.0 outside every segment). */
    fun rateAt(songMs: Long): Double {
        for (segment in segments) {
            if (songMs < segment.songStartMs) return 1.0
            if (songMs < segment.songEndMs) return segment.rate
        }
        return 1.0
    }

    /**
     * The offset of the segment nearest to [songMs], for callers that need a number even where the
     * song has no picture. Zero for an empty map.
     */
    fun offsetNear(songMs: Long): Long {
        offsetAt(songMs)?.let { return it }
        var previous: SyncSegment? = null
        for (segment in segments) {
            if (songMs < segment.songStartMs) {
                // in a gap (or before the first segment): whichever neighbour is closer
                val before = previous ?: return segment.offsetMs
                return if (songMs - before.songEndMs <= segment.songStartMs - songMs) before.offsetAtMs(before.songEndMs) else segment.offsetMs
            }
            previous = segment
        }
        return previous?.offsetAtMs(previous.songEndMs) ?: 0L // past the end: the last segment's offset
    }

    /** Whether the song has a matching picture at [songMs]. */
    fun covers(songMs: Long): Boolean = offsetAt(songMs) != null

    override fun equals(other: Any?): Boolean = other is SyncMap && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "SyncMap($segments)"

    companion object {
        /** A map for a video that is the song shifted by one constant [offsetMs] for its whole length. */
        fun constant(offsetMs: Long): SyncMap = SyncMap(listOf(SyncSegment(0L, Long.MAX_VALUE, offsetMs)))

        private fun normalise(input: List<SyncSegment>): List<SyncSegment> {
            val sorted = input.sortedBy { it.songStartMs }
            val out = ArrayList<SyncSegment>(sorted.size)
            for (segment in sorted) {
                val previous = out.lastOrNull()
                if (previous == null || segment.songStartMs >= previous.songEndMs) {
                    out += segment
                } else if (segment.songEndMs > previous.songEndMs) {
                    // overlaps the previous one: it starts where the previous ends
                    out += SyncSegment(previous.songEndMs, segment.songEndMs, segment.offsetAtMs(previous.songEndMs), segment.ncc, segment.rate)
                }
            }
            return out
        }
    }
}
