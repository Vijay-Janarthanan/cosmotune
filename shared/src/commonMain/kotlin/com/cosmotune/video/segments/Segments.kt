package com.cosmotune.video.segments

import com.cosmotune.video.settings.SegmentBehaviour

/** SponsorBlock's action types (spec 7.1: `actionTypes=["skip","mute","poi","full"]`, plus chapters). */
enum class SegmentAction { SKIP, MUTE, FULL, POI, CHAPTER }

/**
 * SponsorBlock's categories (spec 7.1): the API name, the default behaviour, the actions the category allows and the
 * colour on the scrubber (the extension's `barTypes` defaults, read in `ajayyy/SponsorBlock` `src/config.ts` on
 * 2026-10-16; fixed colours on the scrubber only, a documented exception of the look). The `chapter` category is not
 * here: the video's own chapters are used.
 */
enum class SegmentCategory(val apiName: String, val defaultBehaviour: SegmentBehaviour, val actions: Set<SegmentAction>, val colorArgb: Long) {
    /** Paid promotion: skipped by default. */
    SPONSOR("sponsor", SegmentBehaviour.SKIP, setOf(SegmentAction.SKIP, SegmentAction.MUTE, SegmentAction.FULL), 0xFF00D400),

    /** Unpaid self-promotion: asks. */
    SELF_PROMO("selfpromo", SegmentBehaviour.ASK, setOf(SegmentAction.SKIP, SegmentAction.MUTE, SegmentAction.FULL), 0xFFFFFF00),

    /** Interaction reminders ("subscribe"): asks. */
    INTERACTION("interaction", SegmentBehaviour.ASK, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFFCC00FF),

    /** Intro animation: shown on the bar. */
    INTRO("intro", SegmentBehaviour.SHOW, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFF00FFFF),

    /** Outro and end cards: shown. */
    OUTRO("outro", SegmentBehaviour.SHOW, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFF0202ED),

    /** Preview and recap: shown. */
    PREVIEW("preview", SegmentBehaviour.SHOW, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFF008FD6),

    /** Hook (a teaser at the start): shown. */
    HOOK("hook", SegmentBehaviour.SHOW, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFF395699),

    /** The non-music part of a music video: shown (Skip when the video is in the Music category, spec 7.1). */
    MUSIC_OFFTOPIC("music_offtopic", SegmentBehaviour.SHOW, setOf(SegmentAction.SKIP), 0xFFFF9900),

    /** Filler and tangents: off (the wiki advises against it by default). */
    FILLER("filler", SegmentBehaviour.OFF, setOf(SegmentAction.SKIP, SegmentAction.MUTE), 0xFF7300FF),

    /** The highlight: a mark on the bar and a "Jump to highlight" chip. */
    POI_HIGHLIGHT("poi_highlight", SegmentBehaviour.SHOW, setOf(SegmentAction.POI), 0xFFFF1684),

    /** Exclusive access (the whole video is promotion): a label on the watch page. */
    EXCLUSIVE_ACCESS("exclusive_access", SegmentBehaviour.SHOW, setOf(SegmentAction.FULL), 0xFF008A5C);

    companion object {
        /** Each category's default behaviour (spec 7.1): the defaults of `VideoSettings.segmentBehaviours`. */
        fun defaults(): Map<SegmentCategory, SegmentBehaviour> = entries.associateWith { it.defaultBehaviour }

        /** The category of a SponsorBlock API name ("sponsor", "selfpromo", ...), or null for an unknown one. */
        fun of(apiName: String): SegmentCategory? = entries.firstOrNull { it.apiName == apiName }
    }
}

/**
 * One SponsorBlock segment (spec 7.1): its UUID, category and action, start and end, the duration of the video it was
 * submitted on (segments more than 2 s off the video's duration are dropped), its votes, whether a moderator locked it,
 * and its description.
 */
data class SkipSegment(
    val uuid: String,
    val category: SegmentCategory,
    val action: SegmentAction,
    val startMs: Long,
    val endMs: Long,
    val videoDurationMs: Long?,
    val votes: Int,
    val locked: Boolean,
    val description: String?,
)

/** SponsorBlock's segments of a video for the enabled categories (spec 7.1; the client is plan task Y-6). */
interface SegmentProvider {
    /** The segments of [videoId] in [categories]; empty when the video has none. */
    suspend fun segments(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment>
}

/** DeArrow's branding of a video (spec 7.2): a better title, the moment of a better thumbnail and its address. */
data class Branding(val title: String?, val thumbnailTimeMs: Long?, val thumbnailUrl: String?)

/** DeArrow (spec 7.2; the client is plan task Y-6). */
interface BrandingProvider {
    /** The branding of [videoId], or null when DeArrow has none worth using. */
    suspend fun branding(videoId: String, durationMs: Long?): Branding?
}

/** Return YouTube Dislike's counts (spec 7.3). */
data class Votes(val likes: Long?, val dislikes: Long?, val viewCount: Long?)

/** Return YouTube Dislike (spec 7.3; the client is plan task Y-6). */
interface VotesProvider {
    /** The counts of [videoId], or null when the service has none or is rate-limited. */
    suspend fun votes(videoId: String): Votes?
}
