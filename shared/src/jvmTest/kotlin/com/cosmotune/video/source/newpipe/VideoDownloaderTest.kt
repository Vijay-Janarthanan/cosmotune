package com.cosmotune.video.source.newpipe

import com.cosmotune.music.data.TrackLog
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Video's downloader (plan task Y-4, step 2): its own client with no cookie jar and no interceptors, request headers copied
 * as Music's downloader copies them, HTTP 429 as NewPipe's `ReCaptchaException`, a log line per request that never holds the
 * query string. A local server stands in for the source; nothing here leaves the machine.
 */
class VideoDownloaderTest {
    private val server = MockWebServer()
    private val downloader = VideoDownloader()
    private val lines = mutableListOf<String>()
    private var previousEcho: ((Char, String, String, Throwable?) -> Unit)? = null

    @BeforeTest
    fun setUp() {
        server.start()
        previousEcho = TrackLog.echo
        TrackLog.echo = { _, _, message, _ -> synchronized(lines) { lines += message } }
    }

    @AfterTest
    fun tearDown() {
        TrackLog.echo = previousEcho
        server.shutdown()
    }

    private fun get(path: String, headers: Map<String, List<String>> = emptyMap()): org.schabi.newpipe.extractor.downloader.Response =
        downloader.execute(Request.newBuilder().get(server.url(path).toString()).headers(headers).automaticLocalizationHeader(false).build())

    @Test
    fun theClientHasNoCookieJarNoInterceptorsAndTheLimitsOfThePlan() {
        val client = VideoDownloader.newClient()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
    }

    @Test
    fun theDefaultUserAgentIsTheOneOfNewPipesOwnTestDownloader() {
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0", VideoDownloader.DEFAULT_USER_AGENT)
        server.enqueue(MockResponse().setBody("ok"))
        get("/watch?v=x")
        assertEquals(VideoDownloader.DEFAULT_USER_AGENT, server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("User-Agent"))
    }

    @Test
    fun aUserAgentOfTheRequestIsKept() {
        server.enqueue(MockResponse().setBody("ok"))
        get("/player", mapOf("User-Agent" to listOf("com.google.android.youtube/21.03.36 (Linux; U; Android 15; GB) gzip")))
        assertEquals("com.google.android.youtube/21.03.36 (Linux; U; Android 15; GB) gzip", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("User-Agent"))
    }

    @Test
    fun requestHeadersAreCopiedAndAHeaderWithSeveralValuesKeepsThemAll() {
        server.enqueue(MockResponse().setBody("ok"))
        get("/page", mapOf("X-Goog-Visitor-Id" to listOf("abc"), "Accept-Language" to listOf("en-GB, en;q=0.9"), "X-Multi" to listOf("one", "two")))
        val seen = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("abc", seen.getHeader("X-Goog-Visitor-Id"))
        assertEquals("en-GB, en;q=0.9", seen.getHeader("Accept-Language"))
        assertEquals(listOf("one", "two"), seen.headers.values("X-Multi"))
    }

    @Test
    fun aPostCarriesItsBodyAndItsContentType() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val body = """{"context":{"client":{"clientName":"WEB"}}}""".toByteArray()
        val response = downloader.execute(
            Request.newBuilder().post(server.url("/youtubei/v1/next?prettyPrint=false").toString(), body)
                .setHeader("Content-Type", "application/json").automaticLocalizationHeader(false).build(),
        )
        assertEquals(200, response.responseCode())
        assertEquals("""{"ok":true}""", response.responseBody())
        val seen = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", seen.method)
        assertEquals("/youtubei/v1/next?prettyPrint=false", seen.path)
        assertEquals(String(body), seen.body.readUtf8())
        assertTrue(seen.getHeader("Content-Type")?.startsWith("application/json") == true)
    }

    @Test
    fun theAnswerCarriesItsCodeHeadersBodyAndFinalAddress() {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("X-Thing", "a").setBody("not here"))
        val response = get("/missing")
        assertEquals(404, response.responseCode())
        assertEquals("not here", response.responseBody())
        assertEquals("a", response.getHeader("X-Thing"))
        assertEquals(server.url("/missing").toString(), response.latestUrl())
    }

    @Test
    fun aRedirectIsFollowedAndReportsTheLastAddress() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/final").toString()))
        server.enqueue(MockResponse().setBody("there"))
        val response = get("/start")
        assertEquals("there", response.responseBody())
        assertEquals(server.url("/final").toString(), response.latestUrl())
    }

    @Test
    fun httpTooManyRequestsIsNewPipesReCaptcha() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        val url = server.url("/watch?v=x").toString()
        val error = assertFailsWith<ReCaptchaException> { downloader.execute(Request.newBuilder().get(url).automaticLocalizationHeader(false).build()) }
        assertEquals(url, error.url)
    }

    @Test
    fun noCookieIsKeptBetweenRequests() {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "VISITOR_INFO1_LIVE=abc123; Path=/").setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        get("/one")
        get("/two")
        server.takeRequest(5, TimeUnit.SECONDS)
        val second = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(second.getHeader("Cookie"), "Video carries no session")
    }

    @Test
    fun aCookieHeaderTheExtractorSendsGoesThroughUntouched() {
        // NewPipe sets the consent cookie itself as a request header; that is the extractor's, not a stored session.
        server.enqueue(MockResponse().setBody("ok"))
        get("/consent", mapOf("Cookie" to listOf("CONSENT=YES+cb")))
        assertEquals("CONSENT=YES+cb", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Cookie"))
    }

    @Test
    fun everyRequestIsLoggedWithMethodHostAndPathButNeverTheQuery() {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setResponseCode(429))
        get("/youtubei/v1/search?query=my+secret+search&visitorData=SECRETVALUE&key=KEY")
        runCatching { get("/youtubei/v1/next?token=TOKENVALUE") }
        val logged = synchronized(lines) { lines.toList() }
        assertEquals(2, logged.size, logged.toString())
        assertTrue(logged[0].contains("GET") && logged[0].contains("${server.hostName}/youtubei/v1/search"), logged[0])
        assertTrue(logged[0].contains("ms"), logged[0])
        assertTrue(logged[1].contains("429") && logged[1].contains("/youtubei/v1/next"), logged[1])
        logged.forEach { line ->
            assertFalse("secret" in line || "SECRETVALUE" in line || "TOKENVALUE" in line || "KEY" in line || "query=" in line || "?" in line, line)
        }
    }

    @Test
    fun aFailedRequestIsLoggedWithoutItsQueryAndRethrown() {
        val url = server.url("/gone?token=TOKENVALUE").toString()
        server.shutdown()
        assertFailsWith<java.io.IOException> { downloader.execute(Request.newBuilder().get(url).automaticLocalizationHeader(false).build()) }
        val logged = synchronized(lines) { lines.toList() }
        assertEquals(1, logged.size, logged.toString())
        assertTrue(logged[0].contains("FAILED") && logged[0].contains("/gone"), logged[0])
        assertFalse("TOKENVALUE" in logged[0] || "?" in logged[0], logged[0])
    }

    @Test
    fun theLoggedFormIsHostAndPathOnly() {
        assertEquals("www.youtube.com/youtubei/v1/next", VideoDownloader.hostAndPath("https://www.youtube.com/youtubei/v1/next?prettyPrint=false&key=abc#frag"))
        assertEquals("127.0.0.1/a/b", VideoDownloader.hostAndPath("http://user:pw@127.0.0.1:8080/a/b?x=1"))
        assertEquals("(unparsed)", VideoDownloader.hostAndPath("not a url"))
    }
}
