package com.cosmotune.video.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which feed address a channel is read from, when it is due, and the numbers of spec 8.3 (plan task Y-9, step 2). */
class FeedPolicyTest {

    @Test
    fun theNumbersAreThoseOfTheSpec() {
        assertEquals(listOf(1_000L, 4_000L), FeedPolicy.RETRY_DELAYS_MS)
        assertEquals(6, FeedPolicy.CONCURRENCY)
        assertEquals(30 * 60_000L, FeedPolicy.FRESH_MS)
        assertEquals(1_800_000L, FeedPolicy.FRESH_MS)
    }

    @Test
    fun withShortsShownTheChannelFeedIsRead() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA",
            FeedPolicy.feedUrl("UCX6OQ3DkcsbYNE6H8uQQuVA", hideShorts = false),
        )
    }

    @Test
    fun withShortsHiddenTheLongFormUploadsPlaylistIsRead() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULFX6OQ3DkcsbYNE6H8uQQuVA",
            FeedPolicy.feedUrl("UCX6OQ3DkcsbYNE6H8uQQuVA", hideShorts = true),
        )
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULFsXVk37bltHxD1rDPwtNM8Q",
            FeedPolicy.feedUrl("UCsXVk37bltHxD1rDPwtNM8Q", hideShorts = true),
        )
    }

    @Test
    fun anIdWithoutItsPrefixIsNotCutAgain() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULFX6OQ3DkcsbYNE6H8uQQuVA",
            FeedPolicy.feedUrl("X6OQ3DkcsbYNE6H8uQQuVA", hideShorts = true),
        )
    }

    @Test
    fun aChannelNeverFetchedIsDue() {
        assertTrue(FeedPolicy.due(lastFetchMs = null, nowMs = 5_000_000L, force = false))
    }

    @Test
    fun aChannelFetchedInTheLastThirtyMinutesIsSkipped() {
        val now = 10_000_000L
        assertFalse(FeedPolicy.due(lastFetchMs = now, nowMs = now, force = false))
        assertFalse(FeedPolicy.due(lastFetchMs = now - 29 * 60_000L, nowMs = now, force = false))
        assertFalse(FeedPolicy.due(lastFetchMs = now - FeedPolicy.FRESH_MS + 1, nowMs = now, force = false))
    }

    @Test
    fun aChannelFetchedThirtyMinutesAgoOrEarlierIsDue() {
        val now = 10_000_000L
        assertTrue(FeedPolicy.due(lastFetchMs = now - FeedPolicy.FRESH_MS, nowMs = now, force = false))
        assertTrue(FeedPolicy.due(lastFetchMs = now - 31 * 60_000L, nowMs = now, force = false))
    }

    @Test
    fun aPullToRefreshFetchesEveryChannel() {
        val now = 10_000_000L
        assertTrue(FeedPolicy.due(lastFetchMs = now, nowMs = now, force = true))
        assertTrue(FeedPolicy.due(lastFetchMs = null, nowMs = now, force = true))
    }

    @Test
    fun aFetchTimeInTheFutureIsDueBecauseTheClockWentBack() {
        assertTrue(FeedPolicy.due(lastFetchMs = 20_000_000L, nowMs = 10_000_000L, force = false))
    }

    @Test
    fun onlyTheNotFoundAndTheServerErrorsAreRetried() {
        for (status in listOf(404, 500, 502, 503, 504, 599)) assertTrue(FeedPolicy.retriable(status), "status $status")
        for (status in listOf(200, 204, 301, 400, 401, 403, 410, 429, 600)) assertFalse(FeedPolicy.retriable(status), "status $status")
    }
}
