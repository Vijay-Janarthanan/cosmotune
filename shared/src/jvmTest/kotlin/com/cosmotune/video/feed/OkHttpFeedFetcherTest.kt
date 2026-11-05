package com.cosmotune.video.feed

import com.cosmotune.video.feed.rss.OkHttpFeedFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real fetcher against a local server on the loopback address (plan task Y-9): the answer with its status, no cookies in
 * either direction, cancellation, and the size limit. Nothing leaves the machine.
 */
class OkHttpFeedFetcherTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        runCatching { server.shutdown() }
    }

    @Test
    fun anOkAnswerComesBackWithItsStatusAndBody() = runBlocking {
        server.enqueue(MockResponse().setBody("<feed>café</feed>"))
        val response = OkHttpFeedFetcher().get(server.url("/feeds/videos.xml?channel_id=UCabc").toString())
        assertEquals(200, response.status)
        assertEquals("<feed>café</feed>", response.body)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/feeds/videos.xml?channel_id=UCabc", request.path)
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun aNotFoundAnswerIsAnAnswerNotAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(FeedFixtures.notFoundBody))
        val response = OkHttpFeedFetcher().get(server.url("/feeds/videos.xml").toString())
        assertEquals(404, response.status)
        assertEquals(FeedFixtures.notFoundBody, response.body)
    }

    @Test
    fun aServerErrorIsAnAnswerToo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val response = OkHttpFeedFetcher().get(server.url("/feeds/videos.xml").toString())
        assertEquals(500, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun aRedirectIsFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/final").toString()))
        server.enqueue(MockResponse().setBody("<feed/>"))
        val response = OkHttpFeedFetcher().get(server.url("/start").toString())
        assertEquals(200, response.status)
        assertEquals("<feed/>", response.body)
    }

    @Test
    fun noCookieIsKeptOrSent() = runBlocking {
        server.enqueue(MockResponse().setBody("one").addHeader("Set-Cookie", "VISITOR_INFO1_LIVE=abc; Path=/"))
        server.enqueue(MockResponse().setBody("two"))
        val fetcher = OkHttpFeedFetcher()
        fetcher.get(server.url("/a").toString())
        fetcher.get(server.url("/b").toString())
        assertNull(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Cookie"))
        assertNull(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("Cookie"))
        assertSame(CookieJar.NO_COOKIES, OkHttpFeedFetcher.defaultClient().cookieJar)
    }

    @Test
    fun noAnswerAtAllIsAnIoError() {
        val url = server.url("/gone").toString()
        server.shutdown()
        assertFailsWith<IOException> { runBlocking { OkHttpFeedFetcher().get(url) } }
    }

    @Test
    fun cancellingTheCallerCancelsTheRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val job = async(Dispatchers.Default) { OkHttpFeedFetcher().get(server.url("/slow").toString()) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "the request reached the server")
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue((System.nanoTime() - started) < 5_000_000_000L, "the cancellation did not wait for the answer")
    }

    @Test
    fun aVeryLongBodyIsCutAtTheLimit() = runBlocking {
        val long = "x".repeat((OkHttpFeedFetcher.MAX_BODY_BYTES + 100_000).toInt())
        server.enqueue(MockResponse().setBody(long))
        val response = OkHttpFeedFetcher().get(server.url("/long").toString())
        assertEquals(200, response.status)
        assertEquals(OkHttpFeedFetcher.MAX_BODY_BYTES.toInt(), response.body.length)
    }

    @Test
    fun severalRequestsRunAtOnce() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setBody("ok$it").setHeadersDelay(300, TimeUnit.MILLISECONDS)) }
        val fetcher = OkHttpFeedFetcher()
        val started = System.nanoTime()
        val jobs = (1..6).map { async(Dispatchers.Default) { fetcher.get(server.url("/p$it").toString()) } }
        val answers = jobs.map { it.await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(6, answers.count { it.status == 200 })
        assertTrue(elapsedMs < 1_500, "six requests of 300 ms took $elapsedMs ms: they ran one after the other")
    }
}
