package com.cosmotune.video.segments

import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DeArrow client (spec 2.3, 7.2) against a local server; nothing here touches the network. */
class DeArrowClientTest {
    private lateinit var server: MockWebServer
    private var now = 5_000_000L
    private val target = "dQw4w9WgXcQ"
    private val thumbBase = "https://thumb.example"

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        DebugLog.sink = null
        runCatching { server.shutdown() }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/video/segments/$name")) { "missing fixture $name" }.use { it.readBytes().decodeToString() }

    private fun client(base: String = server.url("/").toString().trimEnd('/'), thumbs: String = thumbBase) =
        DeArrowClient(baseUrl = base, thumbBase = thumbs, http = OkHttpClient(), clock = { now })

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    /** One DeArrow answer for [target] with the given titles and thumbnails (JSON objects, comma separated). */
    private fun answer(titles: String = "", thumbnails: String = "", videoDuration: String = "212.1") =
        """{"$target":{"titles":[$titles],"thumbnails":[$thumbnails],"randomTime":0.4,"videoDuration":$videoDuration}}"""

    private fun title(text: String, votes: Int = 1, locked: Boolean = false, original: Boolean = false) =
        """{"title":"$text","original":$original,"votes":$votes,"locked":$locked,"UUID":"t-$votes"}"""

    private fun thumb(timestamp: String?, votes: Int = 1, locked: Boolean = false, original: Boolean = false) =
        """{"timestamp":${timestamp ?: "null"},"original":$original,"votes":$votes,"locked":$locked,"UUID":"h-$votes"}"""

    // ---- the request ----

    @Test
    fun theRequestAsksForTheFourCharacterHashPrefix() {
        server.enqueue(json(answer()))
        blocking { client().branding(target, 212_000) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/branding/5f6b", request.requestUrl!!.encodedPath)
        assertNull(request.getHeader("Cookie"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Cosmotune/"), request.getHeader("User-Agent"))
    }

    @Test
    fun theTitleServiceNeverSeesTheVideoIdOnlyThePrefix() {
        server.enqueue(json(answer()))
        blocking { client().branding(target, 212_000) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(target !in request.path!!, request.path)
        assertNull(request.requestUrl!!.queryParameter("videoID"))
    }

    // ---- the answer ----

    @Test
    fun theFirstTrustedTitleAndThumbnailBecomeTheBranding() {
        server.enqueue(json(fixture("dearrow-branding.json")))
        val b = blocking { client().branding(target, 212_000) }
        assertEquals(
            Branding(
                title = "Rick Astley sings >never gonna give you up",
                thumbnailTimeMs = 43_457,
                thumbnailUrl = "https://thumb.example/api/v1/getThumbnail?videoID=dQw4w9WgXcQ&time=43.457",
            ),
            b,
            "the title is returned as DeArrow has it, markers included: the title-case setting is applied later",
        )
    }

    @Test
    fun anUntrustedBrandingIsNothing() {
        // the other video of the same prefix: votes below zero, not locked
        server.enqueue(json(fixture("dearrow-branding.json")))
        assertNull(blocking { client().branding("otherVid001", 300_000) })
    }

    @Test
    fun aTitleIsTrustedWhenLockedOrWhenVotesAreNotNegative() {
        for ((votes, locked, trusted) in listOf(Triple(0, false, true), Triple(5, false, true), Triple(-1, false, false), Triple(-9, true, true), Triple(-9, false, false))) {
            server.enqueue(json(answer(titles = title("T", votes, locked))))
            val b = blocking { client().branding(target, null) }
            assertEquals(if (trusted) "T" else null, b?.title, "votes $votes, locked $locked")
        }
    }

    @Test
    fun aThumbnailIsTrustedByTheSameRule() {
        for ((votes, locked, trusted) in listOf(Triple(0, false, true), Triple(-1, false, false), Triple(-9, true, true))) {
            server.enqueue(json(answer(thumbnails = thumb("10.5", votes, locked))))
            val b = blocking { client().branding(target, null) }
            assertEquals(if (trusted) 10_500L else null, b?.thumbnailTimeMs, "votes $votes, locked $locked")
        }
    }

    @Test
    fun onlyTheFirstTitleAndTheFirstThumbnailAreLookedAt() {
        server.enqueue(json(answer(titles = title("Untrusted first", -3) + "," + title("Good second", 8), thumbnails = thumb("1", -2) + "," + thumb("2", 9))))
        assertNull(blocking { client().branding(target, null) }, "the first of each is not trusted, so nothing is used")
    }

    @Test
    fun anOriginalTitleOrThumbnailMeansNoReplacement() {
        server.enqueue(json(answer(titles = title("The original", 5, original = true), thumbnails = thumb(null, 5, original = true))))
        assertNull(blocking { client().branding(target, null) })

        server.enqueue(json(answer(titles = title("Better", 5), thumbnails = thumb(null, 5, original = true))))
        val b = blocking { client().branding(target, null) }
        assertEquals(Branding("Better", null, null), b)
    }

    @Test
    fun aThumbnailWithoutATimestampIsNoReplacement() {
        server.enqueue(json(answer(titles = title("Better", 5), thumbnails = thumb(null, 5))))
        assertEquals(Branding("Better", null, null), blocking { client().branding(target, null) })
    }

    @Test
    fun aThumbnailAloneIsEnough() {
        server.enqueue(json(answer(thumbnails = thumb("12.0", 3))))
        assertEquals(Branding(null, 12_000, "https://thumb.example/api/v1/getThumbnail?videoID=$target&time=12"), blocking { client().branding(target, null) })
    }

    @Test
    fun theTimeIsWrittenWithoutTrailingZeros() {
        val cases = mapOf("12" to "12", "1.5" to "1.5", "63.457" to "63.457", "0.25" to "0.25", "0" to "0", "7.000" to "7", "100.1" to "100.1")
        for ((given, written) in cases) {
            server.enqueue(json(answer(thumbnails = thumb(given, 1))))
            val b = blocking { client().branding(target, null) }
            assertEquals("https://thumb.example/api/v1/getThumbnail?videoID=$target&time=$written", b?.thumbnailUrl, given)
        }
    }

    @Test
    fun aBlankTitleIsNoTitle() {
        server.enqueue(json(answer(titles = title("   ", 5), thumbnails = thumb("3", 1))))
        assertEquals(Branding(null, 3_000, "https://thumb.example/api/v1/getThumbnail?videoID=$target&time=3"), blocking { client().branding(target, null) })
    }

    @Test
    fun theThumbnailAddressUsesTheConfiguredBaseWithoutADoubledSlash() {
        server.enqueue(json(answer(thumbnails = thumb("5", 1))))
        val b = blocking { client(thumbs = "https://thumb.example/").branding(target, null) }
        assertEquals("https://thumb.example/api/v1/getThumbnail?videoID=$target&time=5", b?.thumbnailUrl)
    }

    // ---- the duration check ----

    @Test
    fun aThumbnailTimeFromAnotherCutOfTheVideoIsNotUsedButTheTitleIs() {
        server.enqueue(json(answer(titles = title("Better", 5), thumbnails = thumb("43.5", 5), videoDuration = "212.1")))
        val b = blocking { client().branding(target, 600_000) }
        assertEquals(Branding("Better", null, null), b)
    }

    @Test
    fun theDurationsMayDifferByTwoSeconds() {
        server.enqueue(json(answer(thumbnails = thumb("43.5", 5), videoDuration = "212.1")))
        val c = client()
        assertEquals(43_500L, blocking { c.branding(target, 214_100) }?.thumbnailTimeMs, "2.0 s apart")
        assertNull(blocking { c.branding(target, 214_101) }?.thumbnailTimeMs, "2.001 s apart")
        assertEquals(43_500L, blocking { c.branding(target, 210_100) }?.thumbnailTimeMs)
        assertEquals(1, server.requestCount, "the raw answer is what is cached, so the check runs again per call")
    }

    @Test
    fun anUnknownDurationOnEitherSideSkipsTheCheck() {
        server.enqueue(json(answer(thumbnails = thumb("43.5", 5), videoDuration = "212.1")))
        val c = client()
        assertEquals(43_500L, blocking { c.branding(target, null) }?.thumbnailTimeMs)
        assertEquals(43_500L, blocking { c.branding(target, 0) }?.thumbnailTimeMs)
        server.enqueue(json(answer(thumbnails = thumb("43.5", 5), videoDuration = "0")))
        assertEquals(43_500L, blocking { client().branding(target, 999_000) }?.thumbnailTimeMs)
    }

    // ---- failures and the cache ----

    @Test
    fun a404MeansNoBrandingWithoutALogLine() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(json("Not Found", code = 404))
        assertNull(blocking { client().branding(target, 212_000) })
        assertTrue(lines.isEmpty(), "$lines")
    }

    @Test
    fun anAnswerIsCachedForADayAndSoIsNoBranding() {
        server.enqueue(json(fixture("dearrow-branding.json")))
        server.enqueue(json(fixture("dearrow-branding.json")))
        server.enqueue(json("Not Found", code = 404))
        val c = client()
        val first = blocking { c.branding(target, 212_000) }
        now += 23 * 3_600_000L
        assertEquals(first, blocking { c.branding(target, 212_000) })
        assertEquals(1, server.requestCount)
        now += 2 * 3_600_000L
        assertEquals(first, blocking { c.branding(target, 212_000) })
        assertEquals(2, server.requestCount, "after 24 hours it asks again")

        // a 404 is remembered too
        assertNull(blocking { c.branding("jNQXAC9IVRw", null) })
        assertNull(blocking { c.branding("jNQXAC9IVRw", null) })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aVideoMissingFromTheAnswerIsRemembered() {
        server.enqueue(json(fixture("dearrow-branding.json")))
        val c = client()
        assertNull(blocking { c.branding("zzzzzzzzzzz", null) })
        assertNull(blocking { c.branding("zzzzzzzzzzz", null) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun errorsGiveNothingAreLoggedAndAreNotRemembered() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(json("not json at all"))
        server.enqueue(json(fixture("dearrow-branding.json")))
        val c = client()
        assertNull(blocking { c.branding(target, 212_000) })
        assertNull(blocking { c.branding(target, 212_000) })
        assertEquals(2, lines.count { "DeArrow" in it }, "$lines")
        assertTrue(lines.none { target in it }, "the video id is not logged")
        assertEquals("Rick Astley sings >never gonna give you up", blocking { c.branding(target, 212_000) }?.title)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aServerThatIsDownGivesNothingAndDoesNotThrow() {
        val c = client()
        server.shutdown()
        assertNull(blocking { c.branding(target, 212_000) })
    }

    @Test
    fun anImpossibleIdMakesNoRequest() {
        val c = client()
        assertNull(blocking { c.branding("", null) })
        assertNull(blocking { c.branding("a/b", null) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun itIsABrandingProvider() {
        server.enqueue(json(fixture("dearrow-branding.json")))
        val provider: BrandingProvider = client()
        assertEquals("Rick Astley sings >never gonna give you up", blocking { provider.branding(target, 212_000) }?.title)
    }
}
