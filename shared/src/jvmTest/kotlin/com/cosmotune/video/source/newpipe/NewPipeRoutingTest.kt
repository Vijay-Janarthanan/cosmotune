package com.cosmotune.video.source.newpipe

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One NewPipe for the whole process, shared by Music and Video (plan task Y-4, step 1, decision W1): the router that
 * [NewPipeHost] installs sends a request to Music's downloader unless the calling thread is inside `video { }`. Two
 * recording downloaders stand in for the two real ones; nothing here touches the network.
 */
class NewPipeRoutingTest {

    /** A downloader that remembers what it was asked and answers with its own name as the body. */
    private class Recording(val name: String) : Downloader() {
        val urls = mutableListOf<String>()

        @Synchronized
        override fun execute(request: Request): Response {
            urls += request.url()
            return Response(200, "OK", emptyMap(), name, request.url())
        }
    }

    private val urls = listOf(
        "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
        "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
        "https://www.youtube.com/s/player/abcd1234/player_ias.vflset/en_US/base.js",
        "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&q=cat",
    )

    private val music = Recording("music")
    private val video = Recording("video")

    @BeforeTest
    fun setUp() {
        NewPipeHost.resetForTests()
        NewPipeHost.videoDownloader = video
    }

    @AfterTest
    fun tearDown() {
        NewPipeHost.resetForTests()
    }

    private fun answerOf(url: String): String = NewPipe.getDownloader().get(url).responseBody()

    @Test
    fun outsideTheScopeEveryRequestReachesMusic() {
        NewPipeHost.install(music)
        urls.forEach { assertEquals("music", answerOf(it), it) }
        assertEquals(urls, music.urls)
        assertTrue(video.urls.isEmpty())
    }

    @Test
    fun insideTheScopeEveryRequestReachesVideoIncludingTheNextEndpoint() {
        NewPipeHost.install(music)
        NewPipeHost.video {
            urls.forEach { assertEquals("video", answerOf(it), it) }
        }
        assertEquals(urls, video.urls)
        assertTrue(urls.any { "/youtubei/v1/next" in it })
        assertTrue(music.urls.isEmpty())
    }

    @Test
    fun theScopeReturnsWhatItsBlockReturns() {
        NewPipeHost.install(music)
        assertEquals(42, NewPipeHost.video { 40 + 2 })
        assertEquals("video", NewPipeHost.video { answerOf(urls[0]) })
    }

    @Test
    fun installingTwiceKeepsOneRouterAndReplacesOnlyTheMusicDownloader() {
        val first = Recording("music-one")
        val second = Recording("music-two")
        NewPipeHost.install(first)
        val router = NewPipe.getDownloader()
        NewPipeHost.install(second)
        assertSame(router, NewPipe.getDownloader())
        assertEquals("music-two", answerOf(urls[0]))
        assertTrue(first.urls.isEmpty())
        NewPipeHost.video { assertEquals("video", answerOf(urls[0])) }
        assertEquals(1, video.urls.size)
    }

    @Test
    fun ensureBeforeAnyInstallRoutesEverythingToVideo() {
        NewPipeHost.ensure()
        assertEquals("video", answerOf(urls[0]))
        NewPipeHost.video { assertEquals("video", answerOf(urls[2])) }
        assertEquals(listOf(urls[0], urls[2]), video.urls)

        // Music arrives later (its resolver installs on first use): from then on it answers outside the scope.
        NewPipeHost.install(music)
        assertEquals("music", answerOf(urls[0]))
        NewPipeHost.video { assertEquals("video", answerOf(urls[0])) }
    }

    @Test
    fun ensureIsIdempotent() {
        NewPipeHost.ensure()
        val router = NewPipe.getDownloader()
        NewPipeHost.ensure()
        NewPipeHost.ensure()
        assertSame(router, NewPipe.getDownloader())
    }

    @Test
    fun nestedScopesRestoreTheOuterOne() {
        NewPipeHost.install(music)
        assertFalse(NewPipeHost.isInVideoScope())
        NewPipeHost.video {
            assertTrue(NewPipeHost.isInVideoScope())
            NewPipeHost.video {
                assertTrue(NewPipeHost.isInVideoScope())
                assertEquals("video", answerOf(urls[0]))
            }
            // The inner scope has ended; the outer one is still open.
            assertTrue(NewPipeHost.isInVideoScope())
            assertEquals("video", answerOf(urls[1]))
        }
        assertFalse(NewPipeHost.isInVideoScope())
        assertEquals("music", answerOf(urls[2]))
        assertEquals(listOf(urls[0], urls[1]), video.urls)
        assertEquals(listOf(urls[2]), music.urls)
    }

    @Test
    fun anExceptionInsideTheScopeStillClearsIt() {
        NewPipeHost.install(music)
        assertFailsWith<IllegalStateException> { NewPipeHost.video { error("boom") } }
        assertFalse(NewPipeHost.isInVideoScope())
        assertEquals("music", answerOf(urls[0]))

        // An exception in an inner scope leaves the outer scope open.
        NewPipeHost.video {
            assertFailsWith<IllegalArgumentException> { NewPipeHost.video { throw IllegalArgumentException("inner") } }
            assertTrue(NewPipeHost.isInVideoScope())
            assertEquals("video", answerOf(urls[1]))
        }
        assertFalse(NewPipeHost.isInVideoScope())
    }

    @Test
    fun theScopeBelongsToTheThreadThatOpenedIt() {
        NewPipeHost.install(music)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seenByVideoThread: String? = null
        val worker = Thread {
            NewPipeHost.video {
                seenByVideoThread = answerOf(urls[0])
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        worker.start()
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        // While the worker is inside its scope, this thread is outside it: Music's calls still reach Music.
        assertEquals("music", answerOf(urls[1]))
        assertFalse(NewPipeHost.isInVideoScope())
        release.countDown()
        worker.join(10_000)
        assertEquals("video", seenByVideoThread)
    }

    @Test
    fun theHostInitialisesNewPipeWithTheDefaultsNewPipeUsesItself() {
        // NewPipe.init(downloader) uses Localization.DEFAULT (en, GB) and the content country taken from it (GB); Music sees no change.
        NewPipeHost.ensure()
        assertEquals(Localization("en", "GB"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("GB"), NewPipe.getPreferredContentCountry())
        assertEquals(Localization.DEFAULT, NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry.DEFAULT, NewPipe.getPreferredContentCountry())
    }

    @Test
    fun setLocalizationChangesTheCountryAndTheLanguageAndSurvivesAReinstall() {
        NewPipeHost.ensure()
        NewPipeHost.setLocalization("IN", "hi")
        assertEquals(Localization("hi", "IN"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("IN"), NewPipe.getPreferredContentCountry())

        // Somebody else initialises NewPipe: the next ensure() puts the router back and keeps the region.
        NewPipe.init(Recording("stranger"))
        NewPipeHost.ensure()
        assertEquals("video", answerOf(urls[0]))
        assertEquals(Localization("hi", "IN"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("IN"), NewPipe.getPreferredContentCountry())
    }

    @Test
    fun setLocalizationBeforeEnsureIsKept() {
        NewPipeHost.setLocalization("DE", "de")
        NewPipeHost.ensure()
        assertEquals(Localization("de", "DE"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("DE"), NewPipe.getPreferredContentCountry())
    }

    @Test
    fun aBlankRegionFallsBackToTheDefaults() {
        NewPipeHost.ensure()
        NewPipeHost.setLocalization("in", "HI")
        assertEquals(Localization("hi", "IN"), NewPipe.getPreferredLocalization(), "codes are normalised: language lower case, country upper case")
        NewPipeHost.setLocalization("", "")
        assertEquals(Localization("en", "GB"), NewPipe.getPreferredLocalization())
        assertEquals(ContentCountry("GB"), NewPipe.getPreferredContentCountry())
    }

    @Test
    fun musicsDownloaderIsNeverAskedAboutVideosRequests() {
        // The reason for the whole design: Music's downloader answers the next endpoint with an empty envelope.
        val emptyNext = object : Downloader() {
            override fun execute(request: Request): Response =
                if ("/youtubei/v1/next" in request.url()) Response(200, "OK", emptyMap(), """{"responseContext":{}}""", request.url())
                else Response(200, "OK", emptyMap(), "real", request.url())
        }
        val real = object : Downloader() {
            override fun execute(request: Request): Response = Response(200, "OK", emptyMap(), "real next", request.url())
        }
        NewPipeHost.videoDownloader = real
        NewPipeHost.install(emptyNext)
        val next = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
        assertEquals("""{"responseContext":{}}""", answerOf(next))
        assertEquals("real next", NewPipeHost.video { answerOf(next) })
    }
}
