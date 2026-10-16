package com.cosmotune.video.segments

import kotlin.jvm.Synchronized

/**
 * SponsorBlock in memory (plan task Y-2, step 3): every video answers [segments] (filtered by the categories asked);
 * [asked] records the video ids. The default is [SAMPLE], so the scrubber has segments to draw.
 */
class FakeSegmentProvider(var segments: List<SkipSegment> = SAMPLE) : SegmentProvider {
    private val log = mutableListOf<String>()

    /** The video ids asked so far, oldest first. */
    val asked: List<String>
        @Synchronized get() = log.toList()

    override suspend fun segments(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment> {
        record(videoId)
        return segments.filter { it.category in categories }
    }

    @Synchronized
    private fun record(videoId: String) {
        log += videoId
    }

    companion object {
        /** An intro (0 to 8 s), a sponsor (30 to 60 s), a self-promotion (120 to 140 s) and a highlight at 200 s (spec 7.1). */
        val SAMPLE: List<SkipSegment> = listOf(
            SkipSegment("fake-intro", SegmentCategory.INTRO, SegmentAction.SKIP, 0, 8_000, null, 3, false, null),
            SkipSegment("fake-sponsor", SegmentCategory.SPONSOR, SegmentAction.SKIP, 30_000, 60_000, null, 12, true, null),
            SkipSegment("fake-selfpromo", SegmentCategory.SELF_PROMO, SegmentAction.SKIP, 120_000, 140_000, null, 2, false, null),
            SkipSegment("fake-highlight", SegmentCategory.POI_HIGHLIGHT, SegmentAction.POI, 200_000, 200_000, null, 5, false, "The best part"),
        )
    }
}

/** DeArrow in memory (plan task Y-2, step 3): every video answers [branding] (null by default: the original title and thumbnail). */
class FakeBrandingProvider(var branding: Branding? = null) : BrandingProvider {
    private val log = mutableListOf<String>()

    /** The video ids asked so far, oldest first. */
    val asked: List<String>
        @Synchronized get() = log.toList()

    override suspend fun branding(videoId: String, durationMs: Long?): Branding? {
        record(videoId)
        return branding
    }

    @Synchronized
    private fun record(videoId: String) {
        log += videoId
    }
}

/** Return YouTube Dislike in memory (plan task Y-2, step 3): every video answers [votes]. */
class FakeVotesProvider(var votes: Votes? = Votes(likes = 12_400, dislikes = 310, viewCount = null)) : VotesProvider {
    private val log = mutableListOf<String>()

    /** The video ids asked so far, oldest first. */
    val asked: List<String>
        @Synchronized get() = log.toList()

    override suspend fun votes(videoId: String): Votes? {
        record(videoId)
        return votes
    }

    @Synchronized
    private fun record(videoId: String) {
        log += videoId
    }
}
