package com.cosmotune.music.data.canvas

import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlin.math.ceil

/**
 * A song-to-video map in which the picture never goes back.
 *
 * The measured map lists, for each stretch of the song, where in the video it is. A music video that
 * is an edit of the song can jump ahead, and the player follows by seeking; but a stretch that sends
 * the picture back to footage already shown is either a wrong match or footage played twice, and
 * either way the viewer sees a replay and a seek's stall. Such a stretch is dealt with here:
 *
 *  * a step back of up to [MISLOCK_MS] is a wrong match (the stretches around it agree on the offset
 *    that was right): it carries on at the offset the song had before it;
 *  * a longer step back is footage the viewer has seen: the song has no picture until the video
 *    moves past what was shown, and the cover shows meanwhile.
 *
 * A map that already only moves forward is returned as it is.
 */
internal object ForwardOnlySyncMap {

    /** A step back this small is a wrong match, not an edit. */
    const val MISLOCK_MS = 5_000L

    /** The player's own tolerance for drift: a step back this small is not a step. */
    private const val TOLERANCE_MS = 150L

    fun of(map: SyncMap): SyncMap {
        val source = map.segments
        if (source.size < 2) return map
        val kept = ArrayList<SyncSegment>(source.size)
        var reached = Long.MIN_VALUE // the furthest point of the video shown so far
        for (segment in source) {
            val fitted = fit(segment, kept.lastOrNull(), reached) ?: continue
            kept += fitted
            reached = maxOf(reached, videoAt(fitted, fitted.songEndMs))
        }
        return if (kept == source) map else SyncMap(kept)
    }

    private fun fit(segment: SyncSegment, previous: SyncSegment?, reached: Long): SyncSegment? {
        if (previous == null) return segment
        val back = reached - videoAt(segment, segment.songStartMs)
        if (back <= TOLERANCE_MS) return segment
        if (back <= MISLOCK_MS && previous.rate == segment.rate) {
            return segment.copy(offsetMs = previous.offsetAtMs(segment.songStartMs))
        }
        val resumeAt = songTimeAt(segment, reached) ?: return null
        if (resumeAt >= segment.songEndMs) return null
        return segment.copy(songStartMs = resumeAt, offsetMs = segment.offsetAtMs(resumeAt))
    }

    /** Where in the video [segment] puts the song at [songMs]. */
    private fun videoAt(segment: SyncSegment, songMs: Long): Long =
        if (songMs == Long.MAX_VALUE) Long.MAX_VALUE else songMs + segment.offsetAtMs(songMs)

    /** The song time at which [segment] reaches [videoMs] in the video, or null when it never does. */
    private fun songTimeAt(segment: SyncSegment, videoMs: Long): Long? {
        // videoTime = songTime * rate + offset - (rate - 1) * start
        val songTime = (videoMs - segment.offsetMs + (segment.rate - 1.0) * segment.songStartMs) / segment.rate
        return songTime.takeIf { it.isFinite() }?.let { ceil(it).toLong() }
    }
}
