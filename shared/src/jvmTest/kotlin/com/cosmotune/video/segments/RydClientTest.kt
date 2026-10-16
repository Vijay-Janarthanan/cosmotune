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

/** The Return YouTube Dislike client (spec 2.3, 7.3) against a local server; nothing here touches the network. */
class RydClientTest {
    private lateinit var server: MockWebServer
    private var now = 10_000_000L
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

    private fun client(limiter: RateLimiter = RateLimiter { now }) =
        RydClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient(), clock = { now }, limiter = limiter)

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    // ---- the request and the answer ----

    @Test
    fun theRequestAsksForTheVideoId() {
        server.enqueue(json(fixture("ryd-votes.json")))
        blocking { client().votes(target) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/votes", request.requestUrl!!.encodedPath)
        assertEquals(target, request.requestUrl!!.queryParameter("videoId"))
        assertNull(request.getHeader("Cookie"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Cosmotune/"), request.getHeader("User-Agent"))
    }

    @Test
    fun theCountsComeFromTheAnswer() {
        server.enqueue(json(fixture("ryd-votes.json")))
        assertEquals(Votes(likes = 18_512_345, dislikes = 432_100, viewCount = 1_500_000_000), blocking { client().votes(target) })
    }

    @Test
    fun missingFieldsAreNull() {
        server.enqueue(json("""{"id":"$target","likes":10}"""))
        assertEquals(Votes(likes = 10, dislikes = null, viewCount = null), blocking { client().votes(target) })
    }

    @Test
    fun anAnswerWithoutAnyCountIsNoVotes() {
        server.enqueue(json("""{"id":"$target","dateCreated":"2021-12-13T00:00:00Z"}"""))
        assertNull(blocking { client().votes(target) })
    }

    @Test
    fun aDeletedVideoHasNoVotes() {
        server.enqueue(json("""{"id":"$target","likes":10,"dislikes":2,"viewCount":100,"deleted":true}"""))
        assertNull(blocking { client().votes(target) })
    }

    // ---- the cache ----

    @Test
    fun anAnswerIsCachedForThreeMinutes() {
        server.enqueue(json(fixture("ryd-votes.json")))
        server.enqueue(json("""{"id":"$target","likes":1,"dislikes":2,"viewCount":3}"""))
        val c = client()
        val first = blocking { c.votes(target) }
        now += 179_999
        assertEquals(first, blocking { c.votes(target) })
        assertEquals(1, server.requestCount)
        now += 1
        assertEquals(Votes(1, 2, 3), blocking { c.votes(target) }, "three minutes on it asks again")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a404IsRememberedForThreeMinutesAsNoVotes() {
        server.enqueue(json("""{"message":"not found"}""", code = 404))
        server.enqueue(json(fixture("ryd-votes.json")))
        val c = client()
        assertNull(blocking { c.votes(target) })
        assertNull(blocking { c.votes(target) })
        assertEquals(1, server.requestCount)
        now += 180_000
        assertEquals(18_512_345L, blocking { c.votes(target) }?.likes)
    }

    // ---- 429 ----

    @Test
    fun a429BacksOffForSixtySecondsForEveryVideo() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        server.enqueue(json(fixture("ryd-votes.json")))
        val c = client()
        assertNull(blocking { c.votes(target) })
        assertEquals(1, lines.count { "ReturnDislike" in it }, "$lines")
        assertEquals(1, server.requestCount)

        now += 59_999
        assertNull(blocking { c.votes(target) }, "still backing off")
        assertNull(blocking { c.votes("jNQXAC9IVRw") }, "any video")
        assertEquals(1, server.requestCount, "no request while backing off")

        now += 1
        assertEquals(18_512_345L, blocking { c.votes(target) }?.likes, "after sixty seconds it asks again")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aCachedAnswerIsStillServedWhileBackingOff() {
        server.enqueue(json(fixture("ryd-votes.json")))
        server.enqueue(MockResponse().setResponseCode(429))
        val c = client()
        assertEquals(18_512_345L, blocking { c.votes(target) }?.likes)
        assertNull(blocking { c.votes("jNQXAC9IVRw") })       // 429: back off
        now += 10_000
        assertEquals(18_512_345L, blocking { c.votes(target) }?.likes, "from memory")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSecond429AfterTheBackOffBacksOffAgain() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        val c = client()
        assertNull(blocking { c.votes(target) })
        now += 60_000
        assertNull(blocking { c.votes(target) })
        assertEquals(2, server.requestCount)
        now += 59_999
        assertNull(blocking { c.votes(target) })
        assertEquals(2, server.requestCount)
    }

    // ---- the rate limiter ----

    @Test
    fun itRefusesBeyondTheLimiterWithoutAskingTheServer() {
        server.enqueue(json(fixture("ryd-votes.json")))
        server.enqueue(json(fixture("ryd-votes.json")))
        server.enqueue(json(fixture("ryd-votes.json")))
        val c = client(RateLimiter(perMinute = 2, perDay = 1000) { now })
        assertEquals(18_512_345L, blocking { c.votes("aaaaaaaaaaa") }?.likes)
        assertEquals(18_512_345L, blocking { c.votes("bbbbbbbbbbb") }?.likes)
        assertNull(blocking { c.votes("ccccccccccc") }, "a third distinct video within the minute")
        assertEquals(2, server.requestCount)
        now += 60_000
        assertEquals(18_512_345L, blocking { c.votes("ccccccccccc") }?.likes)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aCachedAnswerDoesNotUseUpTheLimit() {
        server.enqueue(json(fixture("ryd-votes.json")))
        val c = client(RateLimiter(perMinute = 1, perDay = 1000) { now })
        repeat(10) { assertEquals(18_512_345L, blocking { c.votes(target) }?.likes) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theDefaultLimitIsOneHundredAMinute() {
        repeat(100) { server.enqueue(json(fixture("ryd-votes.json"))) }
        val c = RydClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient(), clock = { now })
        val answered = (0 until 101).count { i -> blocking { c.votes("video%06d".format(i)) } != null }
        assertEquals(100, answered)
        assertEquals(100, server.requestCount, "the 101st never reached the server")
    }

    // ---- failures ----

    @Test
    fun otherErrorsGiveNoVotesAreLoggedAndAreNotRemembered() {
        val lines = mutableListOf<String>()
        DebugLog.sink = DebugLog.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json("{ not json"))
        server.enqueue(json(fixture("ryd-votes.json")))
        val c = client()
        assertNull(blocking { c.votes(target) })
        assertNull(blocking { c.votes(target) })
        assertEquals(2, lines.size, "$lines")
        assertTrue(lines.none { target in it }, "the video id is not logged")
        assertEquals(18_512_345L, blocking { c.votes(target) }?.likes)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aServerThatIsDownGivesNoVotesAndDoesNotThrow() {
        val c = client()
        server.shutdown()
        assertNull(blocking { c.votes(target) })
    }

    @Test
    fun anImpossibleIdMakesNoRequest() {
        val c = client()
        assertNull(blocking { c.votes("") })
        assertNull(blocking { c.votes("a&b=c") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun itIsAVotesProvider() {
        server.enqueue(json(fixture("ryd-votes.json")))
        val provider: VotesProvider = client()
        assertEquals(432_100L, blocking { provider.votes(target) }?.dislikes)
    }
}
