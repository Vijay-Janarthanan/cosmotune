package com.cosmotune.video.shorts

import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.feed.FeedState
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shorts pager's queue (plan task Y-31, step 1; spec 5.15 "Where the queue comes from"): the seed first, then by origin (Home:
 * the feed's Shorts newest first, the Shorts of the related lists of the last three watches, the selected kiosk's, then the related
 * Shorts of the queue's own Shorts; Subscriptions: the feed's Shorts; Channel: the channel's Shorts tab, paged; Search: the search's
 * Shorts, paged; Related: the related Shorts of the Short, then theirs). Only Shorts, no duplicates across pages, and an empty source
 * ends the queue. Run on the fakes of Y-2: nothing here reaches the network.
 */
class ShortsQueueEngineTest {
    private fun s(n: Int): VideoItem = FakeVideoSource.shorts[n - 1]
    private fun v(n: Int): VideoItem = FakeVideoSource.videos[n - 1]
    private fun ids(items: List<VideoItem>) = items.map { it.id }
    private fun shortIds(vararg n: Int) = n.map { s(it).id }

    private fun engine(
        source: VideoSource = FakeVideoSource(),
        feed: SubscriptionFeed = FakeSubscriptionFeed(),
        library: VideoLibrary = FakeVideoLibrary(),
        settings: VideoSettingsRepository = FakeVideoSettingsRepository(),
    ) = ShortsQueueEngine(source, feed, library, settings)

    private fun feedOf(vararg items: VideoItem) = FakeSubscriptionFeed(FeedState(items.toList(), FakeVideoSource.NOW_MS, emptyList(), false))

    /** Every page of [origin], from [ShortsFeed.start] until a page says there is no next one (at most [limit] pages). */
    private suspend fun ShortsFeed.drain(origin: ShortsOrigin, seed: List<VideoItem> = emptyList(), limit: Int = 30): List<ShortsPage> {
        val pages = mutableListOf(start(origin, seed))
        while (pages.last().next != null) {
            assertTrue(pages.size < limit, "the queue of $origin ends")
            pages += more(origin, pages.last().next!!)
        }
        return pages
    }

    private fun List<ShortsPage>.all(): List<VideoItem> = flatMap { it.items }

    /** A source whose kiosk holds a Short ([kioskItems]) and that records the kiosk it was asked for. */
    private class KioskWithAShort(private val inner: FakeVideoSource, private val kioskItems: List<VideoItem>) : VideoSource by inner {
        val kiosksAsked = mutableListOf<String>()
        override suspend fun kiosk(id: String, page: PageToken?): Page<VideoItem> {
            kiosksAsked += id
            return Page(kioskItems, null)
        }
    }

    // ---- the seed ------------------------------------------------------------------------------------------------------------

    @Test fun theSeedComesFirstInItsOwnOrderAndOnlyItsShorts() = runTest {
        val page = engine(feed = feedOf()).start(ShortsOrigin.Subscriptions, seed = listOf(s(5), v(1), s(2), s(5)))
        assertEquals(shortIds(5, 2), ids(page.items), "the card's list, in order, videos and repeats left out")
        assertNull(page.next, "an empty feed after the seed ends the queue")
    }

    @Test fun aShortOfTheSeedIsNotRepeatedByTheOrigin() = runTest {
        val page = engine().start(ShortsOrigin.Subscriptions, seed = listOf(s(3)))
        assertEquals(shortIds(3, 1, 2, 4), ids(page.items))
    }

    // ---- the origins ---------------------------------------------------------------------------------------------------------

    @Test fun subscriptionsIsTheFeedsShortsNewestFirstThenTheEnd() = runTest {
        val source = FakeVideoSource()
        // oldest first on purpose, with a video among them: the engine sorts and filters
        val pages = engine(source = source, feed = feedOf(s(4), v(1), s(1), s(3))).drain(ShortsOrigin.Subscriptions)
        assertEquals(shortIds(1, 3, 4), ids(pages.all()))
        assertEquals(1, pages.size, "one page, then the end")
        assertTrue(source.calls.isEmpty(), "the feed is read from memory: no source call")
    }

    @Test fun aLongFeedIsHandedOutAPageAtATime() = runTest {
        val all = FakeVideoSource.shorts
        val pages = engine(feed = feedOf(*all.toTypedArray())).drain(ShortsOrigin.Subscriptions)
        assertEquals(ShortsQueueEngine.PAGE_SIZE, pages.first().items.size)
        assertEquals(ids(all.sortedByDescending { it.uploadedAtMs }), ids(pages.all()))
        assertNull(pages.last().next)
    }

    @Test fun homeIsTheFeedThenTheRelatedOfTheLastThreeWatchesThenTheKioskThenTheRelatedOfTheQueue() = runTest {
        val inner = FakeVideoSource()
        val source = KioskWithAShort(inner, listOf(v(2), s(10)))
        val library = FakeVideoLibrary()
        for (n in 1..4) library.recordWatch(v(n), 30_000, 600_000)     // history: v4, v3, v2, v1 (the most recent first)
        val settings = FakeVideoSettingsRepository(VideoSettings(homeKiosk = "gaming"))
        val feed = feedOf(s(9), s(8))                                     // s8 is the newer of the two
        val pages = engine(source, feed, library, settings).drain(ShortsOrigin.Home)
        // the feed (s8, s9); every related list of the fakes holds s1 and s2; the kiosk gives s10; the related lists of the queue's
        // Shorts add s3 (s1's related list holds s2 and s3); then nothing new is left and the queue ends
        assertEquals(shortIds(8, 9, 1, 2, 10, 3), ids(pages.all()))
        assertEquals(listOf("gaming"), source.kiosksAsked, "the kiosk Home has selected")
        val details = inner.calls.filter { it.startsWith("details(") }
        assertEquals(listOf("details(fakevideo04)", "details(fakevideo03)", "details(fakevideo02)"), details.take(3), "the last three watches")
        assertTrue("details(fakevideo01)" !in details, "only three")
        assertNull(pages.last().next)
    }

    @Test fun homeSkipsAWatchWhoseRelatedListFails() = runTest {
        val inner = FakeVideoSource()
        val library = FakeVideoLibrary()
        library.recordWatch(v(1), 30_000, 600_000)
        library.recordWatch(v(1).copy(id = "gonevideo01"), 30_000, 600_000)  // the source has no such video
        val pages = engine(source = inner, feed = feedOf(), library = library).drain(ShortsOrigin.Home)
        assertTrue(ids(pages.all()).containsAll(shortIds(1, 2)), "the next watch's related Shorts still come")
        assertTrue("details(gonevideo01)" in inner.calls)
    }

    @Test fun channelIsTheChannelsShortsTabToItsEnd() = runTest {
        val source = FakeVideoSource()
        val pages = engine(source = source).drain(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_2))
        assertEquals(shortIds(7, 8, 9, 10, 11, 12), ids(pages.all()))
        assertEquals(listOf("channelTab(${FakeVideoSource.CHANNEL_2},SHORTS)"), source.calls)
        assertNull(pages.last().next)
    }

    @Test fun searchIsTheSearchsShortsPagedWithNoRepeatAcrossPages() = runTest {
        val source = FakeVideoSource()
        val e = engine(source = source)
        val first = e.start(ShortsOrigin.Search("synth"), seed = listOf(s(11)))
        assertEquals(shortIds(11, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), ids(first.items), "the seed, then the first page of the search")
        val next = assertNotNull(first.next, "the search has a second page")
        val second = e.more(ShortsOrigin.Search("synth"), next)
        assertEquals(shortIds(12), ids(second.items), "s11 came with the seed: skipped")
        assertNull(second.next)
        assertEquals(listOf("search(synth,SHORTS)", "search(synth,SHORTS)"), source.calls)
    }

    @Test fun relatedIsTheSeedThenTheShortsRelatedToItThenTheirs() = runTest {
        val source = FakeVideoSource()
        val pages = engine(source = source).drain(ShortsOrigin.Related(s(5).id), seed = listOf(s(7), s(5)))
        // the seed (s7, s5); s5's related Shorts (s1, s2); then the related lists of the queue add s3; then the end
        assertEquals(shortIds(7, 5, 1, 2, 3), ids(pages.all()))
        val details = source.calls.filter { it.startsWith("details(") }
        assertEquals("details(fakeshort05)", details.first(), "the Short itself first")
        assertEquals(details.distinct(), details, "no related list is read twice")
        assertNull(pages.last().next)
    }

    @Test fun onlyShortsAreEverQueued() = runTest {
        val library = FakeVideoLibrary()
        library.recordWatch(v(3), 30_000, 600_000)
        for (origin in listOf(ShortsOrigin.Home, ShortsOrigin.Related(v(1).id), ShortsOrigin.Search("x"), ShortsOrigin.Subscriptions)) {
            val items = engine(library = library).drain(origin, seed = listOf(v(5))).all()
            assertTrue(items.isNotEmpty() && items.all { it.isShort }, "$origin: Shorts only, got ${ids(items)}")
            assertEquals(ids(items).distinct(), ids(items), "$origin: no repeats")
        }
    }

    // ---- the end and the failures ----------------------------------------------------------------------------------------------

    @Test fun anEmptySourceEndsTheQueue() = runTest {
        val search = engine().start(ShortsOrigin.Search(FakeVideoSource.NO_RESULTS_QUERY))
        assertEquals(emptyList(), search.items)
        assertNull(search.next)
        val feed = engine(feed = feedOf(v(1), v(2))).start(ShortsOrigin.Subscriptions)
        assertEquals(emptyList(), feed.items, "a feed with no Shorts")
        assertNull(feed.next)
    }

    @Test fun aFailingFirstPageThrowsWithNoSeedAndKeepsTheSeedOtherwise() = runTest {
        val source = FakeVideoSource()
        val e = engine(source = source)
        source.failNext(VideoSourceError.Network("offline", null))
        assertFailsWith<VideoSourceError.Network> { e.start(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_1)) }

        source.failNext(VideoSourceError.Network("offline", null))
        val first = e.start(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_1), seed = listOf(s(1)))
        assertEquals(shortIds(1), ids(first.items), "the seed stays")
        val retry = assertNotNull(first.next, "the page that failed is asked again")
        val second = e.more(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_1), retry)
        assertEquals(shortIds(2, 3, 4, 5, 6), ids(second.items))
        assertNull(second.next)
    }

    @Test fun aTokenStillWorksAfterItsSessionWasForgotten() = runTest {
        val first = engine().start(ShortsOrigin.Search("synth"))
        val again = engine().more(ShortsOrigin.Search("synth"), assertNotNull(first.next))
        assertEquals(shortIds(11, 12), ids(again.items), "another engine (a restarted process) reads the same page")
        assertNull(again.next)
        val nonsense = engine().more(ShortsOrigin.Search("synth"), PageToken("not a token"))
        assertEquals(ShortsPage(emptyList(), null), nonsense, "an unreadable token ends the queue")
    }
}
