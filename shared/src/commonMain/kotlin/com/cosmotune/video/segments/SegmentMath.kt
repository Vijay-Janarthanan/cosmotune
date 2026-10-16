package com.cosmotune.video.segments

import com.cosmotune.video.settings.SegmentBehaviour

/**
 * The pure clean-up of a video's SponsorBlock segments (spec 7.1, "Policy (pure)"). Nothing here reads a clock or a
 * network; [SegmentPolicy] decides what to do with the result at each position tick.
 */
object SegmentMath {
    /** Segments shorter than this are ignored (spec 7.1: one second). */
    const val MIN_SEGMENT_MS = 1_000L

    /** A segment submitted on a video whose duration differs from this one's by more than this is dropped (spec 7.1: 2 s, a re-upload). */
    const val DURATION_TOLERANCE_MS = 2_000L

    /**
     * Cleans [segments] for a video of [videoDurationMs] (null or 0 when unknown):
     * - a skip or mute segment shorter than 1 s is dropped; a highlight or an exclusive-access mark has no length and is kept;
     * - a segment whose own `videoDurationMs` differs from the video's by more than 2 s is dropped, when both are known;
     * - overlapping skip or mute segments of the same action and the same behaviour (as [behaviours] sets it, a category
     *   missing from it counting as its default) become one that keeps the first one's UUID and category and runs to the
     *   latest end; segments that only touch stay apart, and marks are never merged;
     * - the result is sorted by start, then end.
     */
    fun prepare(
        segments: List<SkipSegment>,
        videoDurationMs: Long?,
        behaviours: Map<SegmentCategory, SegmentBehaviour> = SegmentCategory.defaults(),
    ): List<SkipSegment> = prepare(segments, videoDurationMs) { a, b ->
        (behaviours[a.category] ?: a.category.defaultBehaviour) == (behaviours[b.category] ?: b.category.defaultBehaviour)
    }

    /**
     * [prepare] with another meaning of "the same behaviour", for a caller that does not know the settings:
     * [sameBehaviour] says whether two overlapping segments (of the same action) may become one. [sameCategory] is the
     * choice that is right whatever the settings are.
     */
    fun prepare(
        segments: List<SkipSegment>,
        videoDurationMs: Long?,
        sameBehaviour: (SkipSegment, SkipSegment) -> Boolean,
    ): List<SkipSegment> {
        val video = videoDurationMs?.takeIf { it > 0 }
        val sorted = segments
            .filter { keep(it, video) }
            .sortedWith(compareBy<SkipSegment> { it.startMs }.thenBy { it.endMs })
        val result = ArrayList<SkipSegment>(sorted.size)
        for (segment in sorted) {
            val target = if (isActionable(segment)) lastMergeable(result, segment, sameBehaviour) else -1
            if (target < 0) {
                result += segment
            } else {
                val into = result[target]
                if (segment.endMs > into.endMs) result[target] = into.copy(endMs = segment.endMs)
            }
        }
        return result
    }

    /** Two segments of the same category may be merged whatever the settings say (they always share a behaviour). */
    fun sameCategory(a: SkipSegment, b: SkipSegment): Boolean = a.category == b.category

    private fun isActionable(s: SkipSegment) = s.action == SegmentAction.SKIP || s.action == SegmentAction.MUTE

    private fun keep(s: SkipSegment, video: Long?): Boolean {
        if (s.startMs < 0 || s.endMs < s.startMs) return false
        if (isActionable(s) && s.endMs - s.startMs < MIN_SEGMENT_MS) return false
        val own = s.videoDurationMs
        if (video != null && own != null && own > 0 && kotlin.math.abs(own - video) > DURATION_TOLERANCE_MS) return false
        return true
    }

    /**
     * The index of the latest merged segment that [segment] overlaps and may join: of the same action and, by
     * [sameBehaviour], the same behaviour. Those of one kind never overlap each other, so the latest is the only candidate.
     */
    private fun lastMergeable(
        result: List<SkipSegment>,
        segment: SkipSegment,
        sameBehaviour: (SkipSegment, SkipSegment) -> Boolean,
    ): Int {
        for (i in result.indices.reversed()) {
            val other = result[i]
            if (!isActionable(other) || other.action != segment.action || !sameBehaviour(other, segment)) continue
            return if (segment.startMs < other.endMs) i else -1
        }
        return -1
    }
}
