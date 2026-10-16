package com.cosmotune.video.player

import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.settings.VideoSettings

/** What the SponsorBlock policy decides at a position tick (spec 6.1, 7.1). */
sealed interface SegmentDecision {
    /** Nothing to do. */
    data object None : SegmentDecision

    /** Skip to [ms], the end of [segment] (with "Undo"). */
    data class SkipTo(val ms: Long, val segment: SkipSegment) : SegmentDecision

    /** Mute until [untilMs], the end of [segment]. */
    data class Mute(val untilMs: Long, val segment: SkipSegment) : SegmentDecision

    /** Show the "Skip" pill for [segment]. */
    data class Ask(val segment: SkipSegment) : SegmentDecision

    /** End a mute: the position left the muted segment. */
    data object Unmute : SegmentDecision
}

/**
 * The SponsorBlock policy (spec 7.1; pure). [handled] holds the UUIDs already skipped or undone in this load;
 * [seekedInto] the UUIDs the user seeked into (never skipped again). The real one is `SegmentPolicy` (plan task Y-6).
 */
interface SegmentDecider {
    /** The decision at [positionMs] for [segments] under [settings]. */
    fun decide(positionMs: Long, segments: List<SkipSegment>, settings: VideoSettings, handled: Set<String>, seekedInto: Set<String>): SegmentDecision
}

/**
 * A decider that never acts, for the fake graph and tests (plan task Y-2). Stateless: write `NoSegmentDecider()` or use
 * the companion, `NoSegmentDecider`, which is the same decider.
 */
class NoSegmentDecider : SegmentDecider {
    /** The shared instance: `NoSegmentDecider` works wherever a [SegmentDecider] is wanted. */
    companion object : SegmentDecider by NoSegmentDecider()

    override fun decide(
        positionMs: Long,
        segments: List<SkipSegment>,
        settings: VideoSettings,
        handled: Set<String>,
        seekedInto: Set<String>,
    ): SegmentDecision = SegmentDecision.None
}
