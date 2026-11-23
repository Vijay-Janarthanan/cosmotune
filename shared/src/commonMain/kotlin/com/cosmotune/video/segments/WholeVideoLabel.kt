package com.cosmotune.video.segments

import com.cosmotune.media.segments.*

import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings

/**
 * SponsorBlock's whole-video labels (the `full` action): a video that is all a paid promotion, all self-promotion, or exclusive
 * access to a product. Cards and the watch page say so in their meta line.
 */
object WholeVideoLabel {
    /** The categories that can label a whole video, the strongest first. */
    val ORDER: List<SegmentCategory> = listOf(SegmentCategory.SPONSOR, SegmentCategory.EXCLUSIVE_ACCESS, SegmentCategory.SELF_PROMO)

    /** The categories to ask for under [settings]: none with SponsorBlock or the labels off, else those not set to Off. */
    fun categories(settings: VideoSettings): Set<SegmentCategory> {
        if (!settings.sponsorBlock || !settings.wholeVideoLabels) return emptySet()
        return ORDER.filter { (settings.segmentBehaviours[it] ?: it.defaultBehaviour) != SegmentBehaviour.OFF }.toSet()
    }

    /** The label of a video with [segments]: the strongest `full` segment's category among [allowed], or null. */
    fun of(segments: List<SkipSegment>, allowed: Set<SegmentCategory>): SegmentCategory? {
        val full = segments.filter { it.action == SegmentAction.FULL && it.category in allowed }.map { it.category }.toSet()
        return ORDER.firstOrNull { it in full }
    }
}
