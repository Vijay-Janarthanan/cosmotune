package com.cosmotune.video.segments

import com.cosmotune.video.player.SegmentDecider
import com.cosmotune.video.player.SegmentDecision
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings

/**
 * The SponsorBlock policy (spec 6.1, 7.1). Pure and stateless: the controller keeps the set of UUIDs already handled
 * (skipped, undone or answered) and the set the user seeked into, and asks at each position tick.
 *
 * At a position inside a skip or mute segment that is neither handled nor seeked into, the category's behaviour decides:
 * Skip gives `SkipTo(end)`, Mute `Mute(end)`, Ask `Ask`, Show and Off nothing. A segment the submitter marked "mute" is
 * muted where the category says Skip (skipping it would cut what the submitter meant to keep). Highlights, exclusive-access
 * marks and chapters never skip. A position just after a segment that was being muted answers `Unmute`. With
 * `settings.sponsorBlock` off the answer is always `None`.
 *
 * Where several eligible segments cover the position, Skip wins over Mute over Ask, and among equals the earlier start;
 * [SegmentMath.prepare] has usually merged such overlaps already. A segment under [SegmentMath.MIN_SEGMENT_MS] is ignored,
 * so a list that was not prepared is safe too. No allocation unless the answer is an action.
 */
object SegmentPolicy : SegmentDecider {
    /**
     * How long after the end of a muted segment the policy answers `Unmute`. The controller ticks every 250 ms and also ends
     * a mute itself when the position leaves the segment; the window makes the answer explicit for any other caller.
     */
    const val UNMUTE_WINDOW_MS = 1_000L

    private const val RANK_ASK = 1
    private const val RANK_MUTE = 2
    private const val RANK_SKIP = 3

    override fun decide(
        positionMs: Long,
        segments: List<SkipSegment>,
        settings: VideoSettings,
        handled: Set<String>,
        seekedInto: Set<String>,
    ): SegmentDecision {
        if (!settings.sponsorBlock) return SegmentDecision.None
        var best: SkipSegment? = null
        var bestRank = 0
        var justLeftMute = false
        for (i in segments.indices) {
            val s = segments[i]
            if (s.action != SegmentAction.SKIP && s.action != SegmentAction.MUTE) continue
            if (s.endMs - s.startMs < SegmentMath.MIN_SEGMENT_MS) continue
            val rank = rankOf(s, settings.segmentBehaviours[s.category] ?: s.category.defaultBehaviour)
            if (rank == 0) continue
            if (positionMs >= s.startMs && positionMs < s.endMs) {
                if (s.uuid in handled || s.uuid in seekedInto) continue
                val current = best
                if (rank > bestRank || (rank == bestRank && current != null && s.startMs < current.startMs)) {
                    best = s
                    bestRank = rank
                }
            } else if (rank == RANK_MUTE && positionMs >= s.endMs && positionMs - s.endMs < UNMUTE_WINDOW_MS) {
                justLeftMute = true
            }
        }
        val chosen = best ?: return if (justLeftMute) SegmentDecision.Unmute else SegmentDecision.None
        return when (bestRank) {
            RANK_SKIP -> SegmentDecision.SkipTo(chosen.endMs, chosen)
            RANK_MUTE -> SegmentDecision.Mute(chosen.endMs, chosen)
            else -> SegmentDecision.Ask(chosen)
        }
    }

    /** 3 skip, 2 mute, 1 ask, 0 nothing: what [behaviour] makes of [segment] (a mute segment is muted under Skip). */
    private fun rankOf(segment: SkipSegment, behaviour: SegmentBehaviour): Int = when (behaviour) {
        SegmentBehaviour.SKIP -> if (segment.action == SegmentAction.MUTE) RANK_MUTE else RANK_SKIP
        SegmentBehaviour.MUTE -> RANK_MUTE
        SegmentBehaviour.ASK -> RANK_ASK
        SegmentBehaviour.SHOW, SegmentBehaviour.OFF -> 0
    }
}
