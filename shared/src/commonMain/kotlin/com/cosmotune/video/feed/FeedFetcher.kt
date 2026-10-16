package com.cosmotune.video.feed

/** What the server answered to a feed request: the HTTP [status] and the [body] as text (the 404 page is an HTML body). */
data class FeedResponse(val status: Int, val body: String)

/**
 * Reads one feed address (plan task Y-9). Any HTTP answer, 404 and 500 included, comes back as a [FeedResponse]; only a
 * request that got no answer at all (no network, a timeout) throws, and the engine treats that as a failed try. The real one
 * is `OkHttpFeedFetcher` (no cookies, no account); tests use a scripted one.
 */
interface FeedFetcher {
    /** GETs [url]. */
    suspend fun get(url: String): FeedResponse
}
