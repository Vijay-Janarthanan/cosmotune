package com.cosmotune.video.feed

/**
 * The rules of the subscription feed that need no clock and no network (plan task Y-9, step 2; spec 8.3): which address a
 * channel is read from, when a channel is due, what is retried, and the numbers.
 */
object FeedPolicy {
    /** Waits before the second and the third try of a feed that answered 404 or 500 (spec 8.3 point 2: "after 1 s and 4 s"). */
    val RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 4_000L)

    /** Channels read at the same time (spec 8.3: "at most 6 at a time"). */
    const val CONCURRENCY: Int = 6

    /** A channel fetched less than this long ago is skipped unless the list was pulled to refresh (spec 8.3: 30 minutes). */
    const val FRESH_MS: Long = 30 * 60_000L

    private const val FEEDS = "https://www.youtube.com/feeds/videos.xml"

    /**
     * The feed address of [channelId] (spec 8.3 point 1). With [hideShorts] on, the long-form upload playlist
     * `UULF<id without "UC">`; with it off, the channel feed, whose Shorts the engine marks from their `/shorts/` links.
     */
    fun feedUrl(channelId: String, hideShorts: Boolean): String =
        if (hideShorts) "$FEEDS?playlist_id=UULF${channelId.removePrefix("UC")}" else "$FEEDS?channel_id=$channelId"

    /**
     * Whether a channel last fetched at [lastFetchMs] is read at [nowMs]: always when [force] (a pull to refresh) or never
     * fetched; otherwise once [FRESH_MS] has passed. A fetch time in the future (the clock was set back) counts as due.
     */
    fun due(lastFetchMs: Long?, nowMs: Long, force: Boolean): Boolean {
        if (force || lastFetchMs == null) return true
        val age = nowMs - lastFetchMs
        return age < 0 || age >= FRESH_MS
    }

    /** Whether an answer with [status] is tried again after a pause: 404 and the 5xx, which the feed gives and then recovers from (spec 2.7). */
    fun retriable(status: Int): Boolean = status == 404 || status in 500..599
}
