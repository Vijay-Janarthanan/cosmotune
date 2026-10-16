package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.source.newpipe.fixtures.ReplayDownloader
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real adapter on recorded answers (plan task Y-4, step 6): searches for "kurzgesagt" (filters `all` and `videos`) and for
 * "#shorts" (the Shorts filter, and its three pages as an `all` search with continuation), recorded with the Recorder, scrubbed,
 * replayed here with the network off. The numbers are those of the recording: a recorded answer does not change.
 *
 * Pinned on purpose: the times of the "7 days ago" kind are worked out by NewPipe from the clock of the day the test runs, so
 * no upload time is compared; and Music's downloader must never be asked.
 */
class SearchReplayTest {
    private val clock = 1_792_152_000_000L

    /** A stand-in for Music's downloader: it answers nothing and remembers being asked. */
    private class MusicWatcher : Downloader() {
        val asked = mutableListOf<String>()
        override fun execute(request: Request): Response {
            asked += request.url()
            throw AssertionError("Music's downloader was asked for ${request.url()} by a Video call")
        }
    }

    private val music = MusicWatcher()

    @BeforeTest
    fun setUp() {
        NewPipeHost.resetForTests()
        YoutubeParsingHelper.resetClientVersion()
        NewPipeHost.install(music)
    }

    @AfterTest
    fun tearDown() {
        NewPipeHost.resetForTests()
        YoutubeParsingHelper.resetClientVersion()
    }

    private fun replay(scenario: String): ReplayDownloader = ReplayDownloader(scenario).also { NewPipeHost.videoDownloader = it }

    private fun source() = NewPipeVideoSource(clock = { clock })

    private fun List<ListItem>.videos() = filterIsInstance<ListItem.Video>().map { it.item }

    // ---------------------------------------------------------------- filter all

    @Test
    fun anAllSearchGivesAChannelAndVideosWithEveryFieldMapped() = runBlocking {
        val replay = replay("search_all")
        val page = source().search("kurzgesagt", SearchFilter.ALL)

        assertEquals(89, page.items.size)
        assertEquals(listOf(0, 29), page.items.withIndex().filter { it.value is ListItem.Channel }.map { it.index })
        assertEquals(87, page.items.videos().size)
        assertTrue(page.items.none { it is ListItem.Playlist })

        val channel = assertIs<ListItem.Channel>(page.items[0]).item
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", channel.id)
        assertEquals("Kurzgesagt – In a Nutshell", channel.name)
        assertEquals(25_700_000L, channel.subscriberCount)
        assertTrue(channel.verified)
        assertTrue(channel.avatarUrl?.startsWith("https://") == true)
        assertTrue(channel.description?.startsWith("Animation videos explaining things") == true)

        val video = page.items[2].let { assertIs<ListItem.Video>(it).item }
        assertEquals("_y9JuojAIg0", video.id)
        assertEquals("We Finally Know More About Humanity’s First Civilization", video.title)
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", video.channelId)
        assertEquals("Kurzgesagt – In a Nutshell", video.channelName)
        assertEquals(1_051L, video.durationSec)
        assertEquals(9_047_140L, video.viewCount)
        assertTrue(video.thumbnailUrl?.startsWith("https://i.ytimg.com/vi/_y9JuojAIg0/hq720.jpg") == true, video.thumbnailUrl)
        assertTrue(video.channelAvatarUrl?.startsWith("https://yt3.ggpht.com/") == true)
        assertNotNull(video.uploadedAtMs)
        assertEquals("7 days ago", video.uploadedText, "an approximate time keeps the source's own words")
        assertFalse(video.isLive || video.isUpcoming || video.isShort)

        // Everything that came back is a playable item.
        page.items.videos().forEach {
            assertEquals(11, it.id.length, it.id)
            assertTrue(it.title.isNotBlank(), it.id)
            assertTrue(it.thumbnailUrl?.startsWith("https://") == true, it.id)
            assertTrue((it.durationSec ?: 0) > 0, it.id)
            assertNotNull(it.channelId, it.id)
            assertNotNull(it.viewCount, it.id)
        }
        assertEquals(87, page.items.videos().map { it.id }.toSet().size, "no video twice on one page")

        // It went through Video's downloader, inside the scope: two requests (the client version, the search), none to Music.
        assertEquals(listOf("GET www.youtube.com/sw.js", "POST www.youtube.com/youtubei/v1/search"), replay.seen)
        assertTrue(music.asked.isEmpty())
        assertTrue(replay.unexpected.isEmpty())
    }

    @Test
    fun theNextPageTokenHoldsTheContinuationRequest() = runBlocking {
        replay("search_all")
        val page = source().search("kurzgesagt", SearchFilter.ALL)
        val token = assertNotNull(page.next)
        val decoded = PageCodec.decode(token)
        assertEquals("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", decoded.page.url)
        assertTrue(decoded.page.id.orEmpty().length > 50, "the continuation token of the source: ${decoded.page.id}")
        assertNull(decoded.context)
    }

    @Test
    fun aBlankQueryAsksNothing() = runBlocking {
        val replay = replay("search_all")
        val page = source().search("   ", SearchFilter.ALL)
        assertTrue(page.items.isEmpty())
        assertNull(page.next)
        assertTrue(replay.seen.isEmpty())
    }

    // ---------------------------------------------------------------- filter videos

    @Test
    fun aVideosSearchGivesOnlyVideos() = runBlocking {
        val replay = replay("search_videos")
        val page = source().search("kurzgesagt", SearchFilter.VIDEOS)
        assertEquals(20, page.items.size)
        assertTrue(page.items.all { it is ListItem.Video })
        assertEquals(listOf("ujkD4SxPKOI", "_y9JuojAIg0", "kGvF7ATAqoo", "yDAAlojz8NU"), page.items.videos().take(4).map { it.id })
        assertEquals(1_304L, page.items.videos()[0].durationSec)
        assertEquals("UCq8ZAAsI89IoJ-fn1gYpO3g", page.items.videos()[2].channelId, "a video of another channel keeps its own")
        assertNotNull(page.next)
        assertEquals(listOf("GET www.youtube.com/sw.js", "POST www.youtube.com/youtubei/v1/search"), replay.seen)
        assertTrue(music.asked.isEmpty())
    }

    // ---------------------------------------------------------------- the Shorts search

    private val firstTenShorts = listOf(
        "v6-3TBOTTak", "LLx-n0cJERQ", "mvWpPHHSdyk", "RFmuTJI2rpc", "rWKHckwXAYQ", "68biFsWOGbc", "NgdRu7dT2-g", "qC12nBdd9T8", "R070nqMY-ZE", "5dnBBKK0uDk",
    )

    @Test
    fun theShortsFilterKeepsOnlyShortsAndReadsUpToThreePages() = runBlocking {
        val replay = replay("search_shorts")
        val page = source().search("#shorts", SearchFilter.SHORTS)

        assertEquals(firstTenShorts, page.items.videos().map { it.id })
        assertTrue(page.items.all { it is ListItem.Video })
        assertTrue(page.items.videos().all { it.isShort }, "only Short items come back")
        assertTrue(page.items.videos().all { (it.durationSec ?: Long.MAX_VALUE) <= 60 })
        assertEquals(10, page.items.videos().map { it.id }.toSet().size)
        // The first page held 7 Shorts, the second 2 more, the third made 10: the search read all three pages, the most it may.
        assertEquals(listOf("GET www.youtube.com/sw.js") + List(3) { "POST www.youtube.com/youtubei/v1/search" }, replay.seen)
        assertNotNull(page.next, "there is more to read")
        assertTrue(music.asked.isEmpty())
    }

    @Test
    fun theShortsTokenContinuesAfterTheLastPageRead() = runBlocking {
        val replay = replay("search_shorts")
        val source = source()
        val first = source.search("#shorts", SearchFilter.SHORTS)
        val token = assertNotNull(first.next)
        val continuation = assertNotNull(PageCodec.decode(token).page.id)
        val before = replay.requests.size

        val second = source.search("#shorts", SearchFilter.SHORTS, token)
        // The first request of the second call is the very continuation the token holds.
        val firstOfSecond = replay.requests[before]
        assertEquals("POST", firstOfSecond.httpMethod())
        assertTrue(continuation in String(assertNotNull(firstOfSecond.dataToSend())), "the request carries the continuation token the token held")
        assertTrue(second.items.all { it is ListItem.Video && it.item.isShort })
        assertTrue(replay.requests.size - before in 1..3, "at most three pages per call")
    }

    @Test
    fun anAllSearchOfTheSameQueryHasTheShortsInItsPagesToo() = runBlocking {
        val replay = replay("search_shorts")
        val source = source()
        val first = source.search("#shorts", SearchFilter.ALL)
        assertEquals(11, first.items.size)
        assertEquals(7, first.items.videos().count { it.isShort })
        val live = first.items.videos().first { it.isLive }
        assertEquals("ZsYUa7MoHc8", live.id)
        assertNull(live.durationSec, "a live stream has no duration")
        assertFalse(live.isShort)

        val second = source.search("#shorts", SearchFilter.ALL, assertNotNull(first.next))
        assertEquals(listOf("H8ZvArQb-2U", "qC12nBdd9T8", "R070nqMY-ZE"), second.items.videos().map { it.id })
        assertEquals(10_489L, second.items.videos()[0].durationSec)
        assertFalse(second.items.videos()[0].isShort)
        assertEquals(3, replay.requests.size, "the client version, then one request per page")
    }

    // ---------------------------------------------------------------- failures while reading

    /** Serves the recording, but fails the [failAt]th search request (counting from 1) with a network error. */
    private fun failingAt(failAt: Int): Downloader {
        val replay = replay("search_shorts")
        val searches = AtomicInteger()
        val failing = object : Downloader() {
            override fun execute(request: Request): Response {
                if (request.httpMethod() == "POST" && searches.incrementAndGet() == failAt) throw IOException("connection reset")
                return replay.execute(request)
            }
        }
        NewPipeHost.videoDownloader = failing
        return failing
    }

    @Test
    fun aPageThatFailsAfterSomeShortsEndsTheCallWithThemAndTheTokenRetriesThatPage() = runBlocking {
        failingAt(3)
        val page = source().search("#shorts", SearchFilter.SHORTS)
        assertEquals(firstTenShorts.take(9), page.items.videos().map { it.id }, "the Shorts of the first two pages")
        assertNotNull(page.next, "the failed page can be read again")
        Unit
    }

    @Test
    fun aPageThatFailsAfterTheFirstEndsTheCallWithTheFirstOnes() = runBlocking {
        failingAt(2)
        val page = source().search("#shorts", SearchFilter.SHORTS)
        assertEquals(firstTenShorts.take(7), page.items.videos().map { it.id })
        assertNotNull(page.next)
        Unit
    }

    @Test
    fun aFirstPageThatFailsIsAnError() {
        failingAt(1)
        assertFailsWith<VideoSourceError.Network> { runBlocking { source().search("#shorts", SearchFilter.SHORTS) } }
    }

    @Test
    fun aFailureOfAnotherKindOfSearchIsAnError() {
        failingAt(1)
        assertFailsWith<VideoSourceError.Network> { runBlocking { source().search("#shorts", SearchFilter.VIDEOS) } }
    }

    // ---------------------------------------------------------------- the rest of the source that needs no page

    @Test
    fun theKiosksAreTheFiveChartsInOrderAndNeverTrending() = runBlocking {
        val replay = replay("search_all")
        val kiosks = source().kiosks()
        assertEquals(listOf("music", "gaming", "movies", "podcasts", "live"), kiosks.map { it.id })
        assertEquals(listOf("Music", "Gaming", "Movies and shows", "Podcasts", "Live"), kiosks.map { it.name })
        assertTrue(replay.seen.isEmpty(), "the list of charts needs no request")
    }

    @Test
    fun anUnknownKioskIsNotAvailable() {
        replay("search_all")
        assertFailsWith<VideoSourceError.NotAvailable> { runBlocking { source().kiosk("trending") } }
        assertFailsWith<VideoSourceError.NotAvailable> { runBlocking { source().kiosk("Trending") } }
    }

    @Test
    fun theSourceNamesNoNewPipeTypeInItsSignatures() {
        val bad = NewPipeVideoSource::class.java.methods.flatMap { m -> m.parameterTypes.toList() + m.returnType }.map { it.name }.filter { it.startsWith("org.schabi") }
        assertTrue(bad.isEmpty(), bad.toString())
    }

    @Test
    fun aMissingRecordingFailsTheTestInsteadOfReachingTheNetwork() {
        val replay = replay("search_all")
        val error = assertFailsWith<AssertionError> {
            NewPipeHost.video { replay.execute(Request.newBuilder().get("https://www.youtube.com/watch?v=jNQXAC9IVRw").build()) }
        }
        assertTrue("no recorded answer" in error.message.orEmpty())
        assertEquals(1, replay.unexpected.size)
    }

    @Test
    fun theTokenOfOneSearchIsNoTokenOfAnotherKindOfCall() {
        replay("search_all")
        assertFailsWith<VideoSourceError.Parsing> { runBlocking { source().search("kurzgesagt", SearchFilter.ALL, PageToken("nonsense")) } }
    }
}
