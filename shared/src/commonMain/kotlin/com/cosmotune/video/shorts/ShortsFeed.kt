package com.cosmotune.video.shorts

import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem

/** Where a Shorts queue comes from (spec 5.15, "Where the queue comes from"). */
sealed interface ShortsOrigin {
    /** Home: your subscriptions' Shorts, then the related lists of your recent watches, the kiosk's, the current one's related. */
    data object Home : ShortsOrigin

    /** Subscriptions: the feed's Shorts, newest first. */
    data object Subscriptions : ShortsOrigin

    /** A channel's Shorts tab, paged. */
    data class Channel(val channelId: String) : ShortsOrigin

    /** The Shorts of a search, paged. */
    data class Search(val query: String) : ShortsOrigin

    /** A single Short opened from a card: that Short, the other Shorts of its list, then its related Shorts. */
    data class Related(val videoId: String) : ShortsOrigin
}

/** One page of a Shorts queue (spec 5.15): Shorts only, duplicates skipped, and the token of the next page or null at the end. */
data class ShortsPage(val items: List<VideoItem>, val next: PageToken?)

/** The pager's queue (spec 5.15; the engine is plan task Y-31, `ShortsQueueEngine`). */
interface ShortsFeed {
    /** The first page for [origin]: the [seed] first (the card's Short and the other Shorts of its list), then by origin. */
    suspend fun start(origin: ShortsOrigin, seed: List<VideoItem> = emptyList()): ShortsPage

    /** The page after [token] (loaded three pages before the end, spec 5.15). */
    suspend fun more(origin: ShortsOrigin, token: PageToken): ShortsPage
}
