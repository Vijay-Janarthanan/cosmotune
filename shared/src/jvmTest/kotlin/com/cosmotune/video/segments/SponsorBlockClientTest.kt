package com.cosmotune.video.segments

import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SponsorBlock client (spec 2.3, 7.1) against a local server; nothing here touches the network. */
class SponsorBlockClientTest {
    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val allCategories = SegmentCategory.entries.toSet()
    private val target = "dQw4w9WgXcQ"

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

    private fun client(http: OkHttpClient = OkHttpClient()) =
        SponsorBlockClient(baseUrl = server.url("/").toString().trimEnd('/'), http = http, clock = { now })

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    // ---- the request ----

    @Test
    fun theRequestAsksForTheHashPrefixWithUrlEncodedJsonArrays() {
        server.enqueue(json("[]"))
        blocking { client().segments(target, setOf(SegmentCategory.INTRO, SegmentCategory.SPONSOR)) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/skipSegments/5f6b", request.requestUrl!!.encodedPath)
        val raw = request.path!!
        assertTrue("categories=%5B%22sponsor%22%2C%22intro%22%5D" in raw, "categories is a URL-encoded JSON array in enum order: $raw")
        assertTrue("actionTypes=%5B%22skip%22%2C%22mute%22%2C%22poi%22%2C%22full%22%5D" in raw, "actionTypes is a URL-encoded JSON array: $raw")
        assertEquals("""["sponsor","intro"]""", request.requestUrl!!.queryParameter("categories"))
        assertEquals("""["skip","mute","poi","full"]""", request.requestUrl!!.queryParameter("actionTypes"))
    }

    @Test
    fun noUserIdAndNoCookieAreSentAndTheAgentNamesTheApp() {
        server.enqueue(json("[]"))
        // a base client that would send a cookie if the client let it
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(Cookie.Builder().name("session").value("secret").domain(url.host).build())
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        }
        blocking { client(OkHttpClient.Builder().cookieJar(jar).build()).segments(target, allCategories) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Cookie"), "no cookies")
        assertNull(request.getHeader("Authorization"))
        assertNull(request.requestUrl!!.queryParameter("userID"), "no user id")
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Cosmotune/"), request.getHeader("User-Agent"))
    }

    @Test
    fun anExplicitAgentIsUsed() {
        server.enqueue(json("[]"))
        val c = SponsorBlockClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient(), clock = { now }, userAgent = "Cosmotune/9.9.9")
        blocking { c.segments(target, allCategories) }
        assertEquals("Cosmotune/9.9.9", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("User-Agent"))
    }

    @Test
    fun theTimeoutsAreTenSecondsWhateverClientIsGiven() {
        val tuned = WebServicesHttp.tuned(OkHttpClient.Builder().readTimeout(1, TimeUnit.HOURS).build())
        assertEquals(10_000, tuned.connectTimeoutMillis)
        assertEquals(10_000, tuned.readTimeoutMillis)
        assertEquals(10_000, tuned.writeTimeoutMillis)
        assertEquals(10_000, tuned.callTimeoutMillis)
        assertEquals(CookieJar.NO_COOKIES, tuned.cookieJar)
        assertEquals(10_000, WebServicesHttp.client.readTimeoutMillis)
        assertEquals(CookieJar.NO_COOKIES, WebServicesHttp.client.cookieJar)
    }

    @Test
    fun theDefaultAgentIsTheAppNameAndAVersion() {
        assertTrue(Regex("""Cosmotune/\S+""").matches(WebServicesHttp.userAgent()), WebServicesHttp.userAgent())
        assertEquals("Cosmotune/2.3.4", WebServicesHttp.userAgent("2.3.4"))
    }

    // ---- the answer ----

    @Test
    fun itPicksItsOwnVideoOutOfTheListAndMapsSecondsToMilliseconds() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val out = blocking { client().segments(target, allCategories) }

        // sorted by start; the sub-second interaction, the unknown category and the other video's segment are gone; the two
        // overlapping sponsors are one with the first one's identity; the re-upload (245 s against 212 s) is kept here because
        // the client does not know the video's duration (see the duration overload below)
        assertEquals(
            listOf("sb-0007-exclusive", "sb-0004-intro-mute", "sb-0001-sponsor", "sb-0003-selfpromo", "sb-0006-highlight", "sb-0009-reupload"),
            out.map { it.uuid },
        )

        val sponsor = out.single { it.uuid == "sb-0001-sponsor" }
        assertEquals(SegmentCategory.SPONSOR, sponsor.category)
        assertEquals(SegmentAction.SKIP, sponsor.action)
        assertEquals(12_500, sponsor.startMs)
        assertEquals(50_000, sponsor.endMs, "merged with the overlapping sponsor")
        assertEquals(212_000, sponsor.videoDurationMs)
        assertEquals(12, sponsor.votes)
        assertTrue(sponsor.locked)
        assertNull(sponsor.description, "an empty description is none")

        val mute = out.single { it.uuid == "sb-0004-intro-mute" }
        assertEquals(SegmentCategory.INTRO, mute.category)
        assertEquals(SegmentAction.MUTE, mute.action)
        assertEquals(0, mute.startMs)
        assertEquals(6_500, mute.endMs)
        assertNull(mute.videoDurationMs, "a videoDuration of 0 is unknown")
        assertEquals("Spoken intro", mute.description)
        assertEquals(false, mute.locked)

        val highlight = out.single { it.uuid == "sb-0006-highlight" }
        assertEquals(SegmentCategory.POI_HIGHLIGHT, highlight.category)
        assertEquals(SegmentAction.POI, highlight.action)
        assertEquals(150_250, highlight.startMs)
        assertEquals(150_250, highlight.endMs)

        val exclusive = out.single { it.uuid == "sb-0007-exclusive" }
        assertEquals(SegmentCategory.EXCLUSIVE_ACCESS, exclusive.category)
        assertEquals(SegmentAction.FULL, exclusive.action)
        assertEquals(0, exclusive.startMs)
        assertEquals(0, exclusive.endMs)
    }

    @Test
    fun theDurationOverloadDropsASegmentFromAnotherCut() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val c = client()
        val out = blocking { c.segments(target, allCategories, videoDurationMs = 212_000) }
        assertTrue(out.none { it.uuid == "sb-0009-reupload" })
        assertTrue(out.any { it.uuid == "sb-0001-sponsor" })
        // asking again with another duration is served from the cache: the raw answer is what is kept
        val other = blocking { c.segments(target, allCategories, videoDurationMs = 245_000) }
        assertEquals(listOf("sb-0009-reupload"), other.filter { it.category == SegmentCategory.OUTRO }.map { it.uuid })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onlyTheRequestedCategoriesComeBack() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val out = blocking { client().segments(target, setOf(SegmentCategory.SPONSOR)) }
        assertEquals(listOf("sb-0001-sponsor"), out.map { it.uuid })
    }

    @Test
    fun aVideoThatIsNotInTheListHasNoSegments() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        assertTrue(blocking { client().segments("zzzzzzzzzzz", allCategories) }.isEmpty())
    }

    @Test
    fun aMissingActionTypeMeansSkipAndLockedMayBeABoolean() {
        server.enqueue(
            json("""[{"videoID":"$target","segments":[{"category":"sponsor","segment":[10,20],"UUID":"old-style","locked":true,"votes":"x"}]}]"""),
        )
        val out = blocking { client().segments(target, allCategories) }
        assertEquals(1, out.size)
        assertEquals(SegmentAction.SKIP, out.single().action)
        assertTrue(out.single().locked)
        assertEquals(0, out.single().votes)
        assertNull(out.single().videoDurationMs)
    }

    @Test
    fun brokenEntriesAreSkippedAndGoodOnesKept() {
        server.enqueue(
            json(
                """[{"videoID":"$target","segments":[
                    {"category":"sponsor","actionType":"skip","segment":[10],"UUID":"one-number"},
                    {"category":"sponsor","actionType":"skip","segment":["a","b"],"UUID":"text-times"},
                    {"category":"sponsor","actionType":"skip","segment":[10,20]},
                    {"category":"sponsor","actionType":"teleport","segment":[10,20],"UUID":"unknown-action"},
                    {"category":"sponsor","actionType":"skip","segment":[30,20],"UUID":"backwards"},
                    {"category":"sponsor","actionType":"skip","segment":[40,50],"UUID":"good"}
                ]}]""",
            ),
        )
        assertEquals(listOf("good"), blocking { client().segments(target, allCategories) }.map { it.uuid })
    }

    // ---- failures ----

    @Test
    fun a404MeansNoSegmentsWithoutALogLine() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(json("Not Found", code = 404))
        assertTrue(blocking { client().segments(target, allCategories) }.isEmpty())
        assertEquals(1, server.requestCount)
        assertTrue(lines.isEmpty(), "a 404 is the normal answer for a video without segments: $lines")
    }

    @Test
    fun a404IsCachedForAnHour() {
        server.enqueue(json("Not Found", code = 404))
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val c = client()
        assertTrue(blocking { c.segments(target, allCategories) }.isEmpty())
        now += 59 * 60_000L
        assertTrue(blocking { c.segments(target, allCategories) }.isEmpty())
        assertEquals(1, server.requestCount, "served from memory")
        now += 2 * 60_000L
        assertTrue(blocking { c.segments(target, allCategories) }.isNotEmpty(), "an hour later it asks again")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anAnswerIsCachedForAnHourPerVideoAndCategorySet() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val c = client()
        val first = blocking { c.segments(target, allCategories) }
        val again = blocking { c.segments(target, allCategories) }
        assertEquals(first, again)
        assertEquals(1, server.requestCount)
        // another set of categories is another question
        blocking { c.segments(target, setOf(SegmentCategory.SPONSOR)) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun otherErrorsGiveNoSegmentsAndALogLineAndAreNotRemembered() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val c = client()
        assertTrue(blocking { c.segments(target, allCategories) }.isEmpty())
        assertTrue(blocking { c.segments(target, allCategories) }.isEmpty())
        assertEquals(2, lines.count { "SponsorBlock" in it }, "one line per failure: $lines")
        assertTrue(lines.all { it.startsWith("W ") }, "warnings: $lines")
        assertTrue(lines.none { target in it }, "the video id is not logged")
        assertTrue(blocking { c.segments(target, allCategories) }.isNotEmpty(), "a failure is not cached")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aBodyThatIsNotJsonGivesNoSegmentsAndALogLine() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(json("<html>Cloudflare says no</html>"))
        assertTrue(blocking { client().segments(target, allCategories) }.isEmpty())
        assertEquals(1, lines.count { "SponsorBlock" in it }, "$lines")
    }

    @Test
    fun aServerThatIsDownGivesNoSegmentsAndDoesNotThrow() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        val c = client()
        server.shutdown()
        assertTrue(blocking { c.segments(target, allCategories) }.isEmpty())
        assertEquals(1, lines.count { "SponsorBlock" in it }, "$lines")
    }

    // ---- no request at all ----

    @Test
    fun noCategoriesAndAnImpossibleIdMakeNoRequest() {
        val c = client()
        assertTrue(blocking { c.segments(target, emptySet()) }.isEmpty())
        assertTrue(blocking { c.segments("", allCategories) }.isEmpty())
        assertTrue(blocking { c.segments("a/b?c=d", allCategories) }.isEmpty())
        assertTrue(blocking { c.segments("x".repeat(65), allCategories) }.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hashPrefixMatchesTheRequestPath() {
        server.enqueue(json("[]"))
        blocking { client().segments("jNQXAC9IVRw", allCategories) }
        assertEquals("/api/skipSegments/${sha256Prefix("jNQXAC9IVRw")}", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
    }

    // ---- the contract ----

    @Test
    fun itIsASegmentProvider() {
        server.enqueue(json(fixture("sponsorblock-skip-segments.json")))
        val provider: SegmentProvider = client()
        val out = blocking { provider.segments(target, setOf(SegmentCategory.SPONSOR, SegmentCategory.SELF_PROMO)) }
        assertEquals(listOf("sb-0001-sponsor", "sb-0003-selfpromo"), out.map { it.uuid })
    }

    @Test
    fun theJsonOfTheRequestIsWellFormed() {
        server.enqueue(json("[]"))
        blocking { client().segments(target, allCategories) }
        val url = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        val categories = Json.parseToJsonElement(url.queryParameter("categories")!!).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SegmentCategory.entries.map { it.apiName }, categories)
        assertNotNull(url.queryParameter("actionTypes"))
    }
}
