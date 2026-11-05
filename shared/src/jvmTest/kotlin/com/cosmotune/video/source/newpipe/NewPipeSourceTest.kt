package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parts of [NewPipeVideoSource] that need no page of the source (plan task Y-4, step 5): the 10-minute cache of the details
 * and the refresh that bypasses it, the addresses handed to NewPipe, the charts' addresses, and what a failing call throws. The
 * watch page itself is replaced by an in-memory `StreamInfo`.
 */
class NewPipeSourceTest {

    @BeforeTest
    fun setUp() {
        NewPipeHost.resetForTests()
        NewPipeHost.ensure()
    }

    @AfterTest
    fun tearDown() {
        NewPipeHost.resetForTests()
    }

    private fun infoFor(url: String, title: String = "A video"): StreamInfo {
        val id = NewPipeMapper.videoIdOf(url) ?: error("no video id in $url")
        return StreamInfo(0, url, url, StreamType.VIDEO_STREAM, id, title, 0).also { it.duration = 120 }
    }

    private class Source(val source: NewPipeVideoSource, val loads: MutableList<String>, val clock: Clock)

    private class Clock(var now: Long)

    private fun source(titles: (Int) -> String = { "Title $it" }): Source {
        val clock = Clock(1_792_152_000_000L)
        val loads = mutableListOf<String>()
        val source = NewPipeVideoSource(clock = { clock.now })
        source.streamInfoLoader = { url ->
            loads += url
            infoFor(url, titles(loads.size))
        }
        return Source(source, loads, clock)
    }

    // ---------------------------------------------------------------- the cache

    @Test
    fun detailsAreAnsweredFromMemoryForTenMinutes() = runTest {
        val s = source()
        val first = s.source.details("jNQXAC9IVRw")
        assertEquals("Title 1", first.item.title)
        assertEquals(1, s.loads.size)
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", s.loads[0])

        s.clock.now += 9 * 60_000L + 59_000L
        assertSame(first, s.source.details("jNQXAC9IVRw"), "the same answer within ten minutes")
        assertEquals(1, s.loads.size)

        s.clock.now += 2_000L
        val later = s.source.details("jNQXAC9IVRw")
        assertEquals(2, s.loads.size, "asked again after ten minutes")
        assertEquals("Title 2", later.item.title)
        assertEquals(s.clock.now, later.fetchedAtMs)
    }

    @Test
    fun theTenMinutesAreTheOnesTheSpecNames() {
        assertEquals(600_000L, NewPipeVideoSource.DETAILS_TTL_MS)
    }

    @Test
    fun anAddressAndAnIdShareOneCacheEntry() = runTest {
        val s = source()
        s.source.details("jNQXAC9IVRw")
        s.source.details("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=30s")
        s.source.details("https://youtu.be/jNQXAC9IVRw")
        assertEquals(1, s.loads.size)
    }

    @Test
    fun differentVideosAreCachedApart() = runTest {
        val s = source()
        val a = s.source.details("jNQXAC9IVRw")
        val b = s.source.details("dQw4w9WgXcQ")
        assertNotEquals(a.item.id, b.item.id)
        assertEquals(2, s.loads.size)
        assertSame(a, s.source.details("jNQXAC9IVRw"))
        assertSame(b, s.source.details("dQw4w9WgXcQ"))
        assertEquals(2, s.loads.size)
    }

    @Test
    fun freshStreamsAreNeverTakenFromTheCacheButRefreshIt() = runTest {
        val s = source()
        val cached = s.source.details("jNQXAC9IVRw")
        s.source.streams("jNQXAC9IVRw")
        assertEquals(2, s.loads.size, "streams() asks the source even with a fresh entry")
        s.source.streams("jNQXAC9IVRw")
        assertEquals(3, s.loads.size)
        val after = s.source.details("jNQXAC9IVRw")
        assertEquals(3, s.loads.size, "and the refreshed answer is what details() gives next")
        assertNotEquals(cached.item.title, after.item.title)
        assertEquals("Title 3", after.item.title)
    }

    @Test
    fun theCacheKeepsTheMostRecentOnesOnly() = runTest {
        val s = source()
        val ids = (0 until 30).map { "vid%08d".format(it) }
        ids.forEach { s.source.details(it) }
        assertEquals(30, s.loads.size)
        // The latest are still held ...
        s.source.details(ids.last())
        assertEquals(30, s.loads.size)
        // ... the first has been forgotten.
        s.source.details(ids.first())
        assertEquals(31, s.loads.size)
    }

    @Test
    fun aRegionChangeForgetsTheCache() = runTest {
        val s = source()
        s.source.details("jNQXAC9IVRw")
        s.source.setRegion("IN", "hi")
        assertEquals(Localization("hi", "IN"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("IN"), NewPipe.getPreferredContentCountry())
        s.source.details("jNQXAC9IVRw")
        assertEquals(2, s.loads.size, "titles come back in the new language")
    }

    @Test
    fun theLoaderRunsInsideVideosScopeOnAnotherThread() = runBlocking {
        val caller = Thread.currentThread()
        var inScope = false
        var ranOn: Thread? = null
        val source = NewPipeVideoSource()
        source.streamInfoLoader = { url ->
            inScope = NewPipeHost.isInVideoScope()
            ranOn = Thread.currentThread()
            infoFor(url)
        }
        source.details("jNQXAC9IVRw")
        assertTrue(inScope, "NewPipe's requests of this call go to Video's downloader")
        assertNotEquals(caller, ranOn, "blocking work does not run on the caller's thread")
        assertTrue(!NewPipeHost.isInVideoScope(), "and the scope has ended with the call")
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun whatTheExtractorThrowsLeavesAsOurErrorAndIsNotCached() = runTest {
        val s = source()
        var fail: Throwable? = PrivateContentException("This video is private")
        s.source.streamInfoLoader = { url ->
            fail?.let { throw it }
            infoFor(url)
        }
        assertFailsWith<VideoSourceError.Private> { s.source.details("jNQXAC9IVRw") }
        fail = IOException("connection reset")
        assertFailsWith<VideoSourceError.Network> { s.source.details("jNQXAC9IVRw") }
        fail = IllegalStateException("the extractor broke")
        assertFailsWith<VideoSourceError.Parsing> { s.source.streams("jNQXAC9IVRw") }
        fail = null
        assertEquals("A video", s.source.details("jNQXAC9IVRw").item.title, "a failure left nothing behind")
    }

    @Test
    fun aCancelledCallIsNotAnError() = runBlocking {
        val source = NewPipeVideoSource()
        source.streamInfoLoader = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { source.details("jNQXAC9IVRw") }
        Unit
    }

    // ---------------------------------------------------------------- addresses

    @Test
    fun watchAndPlaylistAddresses() {
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", NewPipeAddresses.watchUrl("jNQXAC9IVRw"))
        assertEquals("https://youtu.be/jNQXAC9IVRw", NewPipeAddresses.watchUrl("https://youtu.be/jNQXAC9IVRw"))
        assertEquals("https://www.youtube.com/playlist?list=PLabc", NewPipeAddresses.playlistUrl("PLabc"))
    }

    @Test
    fun aChannelIsFoundByIdHandleLegacyNameOrAddress() {
        val id = "UCsXVk37bltHxD1rDPwtNM8Q"
        assertEquals("https://www.youtube.com/channel/$id", NewPipeAddresses.channelUrl(id))
        assertEquals("https://www.youtube.com/channel/$id", NewPipeAddresses.channelUrl("channel/$id"))
        assertEquals("https://www.youtube.com/@kurzgesagt", NewPipeAddresses.channelUrl("@kurzgesagt"))
        assertEquals("https://www.youtube.com/user/kurzgesagt", NewPipeAddresses.channelUrl("user/kurzgesagt"))
        assertEquals("https://www.youtube.com/c/kurzgesagt", NewPipeAddresses.channelUrl("/c/kurzgesagt"))
        assertEquals("https://www.youtube.com/@kurzgesagt", NewPipeAddresses.channelUrl("kurzgesagt"))
        assertEquals("https://www.youtube.com/@kurzgesagt/videos", NewPipeAddresses.channelUrl("https://www.youtube.com/@kurzgesagt/videos"))
        assertEquals("channel/$id", NewPipeAddresses.channelPath(id))
        assertEquals("channel/$id", NewPipeAddresses.channelPath("https://www.youtube.com/channel/$id/shorts"))
        assertEquals("@kurzgesagt", NewPipeAddresses.channelPath("https://www.youtube.com/@kurzgesagt"))
        assertEquals("user/kurzgesagt", NewPipeAddresses.channelPath("https://www.youtube.com/user/kurzgesagt"))
    }

    @Test
    fun theExtractorAcceptsTheAddressOfEveryChannelTab() {
        val service = ServiceList.YouTube
        val id = "UCsXVk37bltHxD1rDPwtNM8Q"
        val expected = mapOf(
            ChannelTab.VIDEOS to "videos",
            ChannelTab.SHORTS to "shorts",
            ChannelTab.LIVE to "livestreams",
            ChannelTab.PLAYLISTS to "playlists",
        )
        for ((tab, filter) in expected) {
            val handler = service.channelTabLHFactory.fromQuery(NewPipeAddresses.channelPath(id), listOf(filter), "")
            assertEquals(id, handler.id.removePrefix("channel/"), tab.name)
            assertTrue(handler.url.startsWith("https://www.youtube.com/channel/$id/"), handler.url)
            assertEquals(listOf(filter), handler.contentFilters)
        }
        // The names the adapter asks for are the extractor's own.
        assertEquals(
            setOf("videos", "shorts", "livestreams", "playlists"),
            service.channelTabLHFactory.availableContentFilter.toSet().intersect(setOf("videos", "shorts", "livestreams", "playlists")),
        )
    }

    @Test
    fun everyChartHasAnAddressTheExtractorOwnsAndNoneIsTrending() {
        val service = ServiceList.YouTube
        val entries = NewPipeVideoSource.KIOSKS
        assertEquals(listOf("music", "gaming", "movies", "podcasts", "live"), entries.map { it.id })
        val source = NewPipeVideoSource()
        val extractorNames = entries.map { entry ->
            val url = source.kioskUrl(entry)
            assertTrue(service.kioskList.getListLinkHandlerFactoryByType(entry.sourceId).acceptUrl(url), url)
            service.kioskList.getExtractorByUrl(url, null).javaClass.simpleName
        }
        assertEquals(
            listOf(
                "YoutubeTrendingMusicExtractor", "YoutubeTrendingGamingVideosExtractor", "YoutubeTrendingMoviesAndShowsTrailersExtractor",
                "YoutubeTrendingPodcastsEpisodesExtractor", "YoutubeLiveExtractor",
            ),
            extractorNames,
        )
        assertTrue(entries.none { it.sourceId.equals("Trending", ignoreCase = true) })
        assertTrue("Trending" in service.kioskList.availableKiosks, "the list still holds the removed chart: that is why the adapter maps ids by hand")
    }
}
