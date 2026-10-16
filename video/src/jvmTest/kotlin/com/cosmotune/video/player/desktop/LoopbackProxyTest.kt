package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.net.GooglevideoRequests
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop engine's loopback proxy (plan task Y-12, step 2; spec 6.3) against a MockWebServer that plays googlevideo: it
 * answers a POST whose address carries `range=a-b` with those bytes (200, as googlevideo does), a GET with a `Range` header with
 * 206 and a `Content-Range`, and can refuse POSTs (405) or say the addresses expired (403). The proxy's client resolves every
 * `*.googlevideo.com` name to this loopback server, so the addresses look real and nothing leaves the machine.
 */
class LoopbackProxyTest {

    private val total = 5 * 1024 * 1024 + 12_345
    private val file = ByteArray(total) { (it * 31 + it / 7).toByte() }
    private val server = MockWebServer()
    private val upstream = Googlevideo()
    private val expired = CopyOnWriteArrayList<String>()
    private lateinit var proxy: LoopbackProxy

    private val loopbackOnly = Dns { host ->
        if (host.endsWith(".googlevideo.com") || host == "localhost") listOf(InetAddress.getByName("127.0.0.1"))
        else throw UnknownHostException("no network in tests: $host")
    }
    private val upstreamClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(loopbackOnly).build()
    private val reader = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(loopbackOnly).readTimeout(20, TimeUnit.SECONDS).build()
    private val headers = mapOf("User-Agent" to "TestAgent/1.0 (fake)", "Origin" to "https://media.example.com")

    @BeforeTest
    fun start() {
        server.dispatcher = upstream
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        proxy = LoopbackProxy(onExpired = { expired += it }, client = upstreamClient)
    }

    @AfterTest
    fun stop() {
        proxy.close()
        server.shutdown()
    }

    /** A googlevideo-looking address served by the mock, with an optional `clen`. */
    private fun videoplayback(itag: Int = 137, clen: Long? = null): String =
        "http://rr1---sn-fake0000.googlevideo.com:${server.port}/videoplayback?expire=1792170000&id=o-FAKE&itag=$itag&c=WEB" +
            (clen?.let { "&clen=$it" } ?: "")

    private class Answer(val code: Int, val headers: okhttp3.Headers, val body: ByteArray)

    private fun get(url: String, range: String? = null, method: String = "GET"): Answer {
        val request = Request.Builder().url(url).method(method, null).apply { if (range != null) header("Range", range) }.build()
        reader.newCall(request).execute().use { r -> return Answer(r.code, r.headers, r.body.bytes()) }
    }

    @Test
    fun aWholeReadIsStitchedFromTwoMebibytePiecesInOrderWithTheStreamsRules() {
        val url = proxy.register(videoplayback(), headers, contentLength = total.toLong())
        assertTrue(Regex("""http://127\.0\.0\.1:\d+/[0-9a-f]{32}/0""").matches(url), url)

        val answer = get(url)

        assertEquals(200, answer.code)
        assertEquals(total.toString(), answer.headers["Content-Length"])
        assertEquals("bytes", answer.headers["Accept-Ranges"])
        assertContentEquals(file, answer.body)
        val seen = upstream.seen.toList()
        assertEquals(listOf("0-2097151", "2097152-4194303", "4194304-${total - 1}"), seen.map { it.rangeParameter })
        assertEquals(listOf("0", "1", "2"), seen.map { it.requestNumber }, "rn counts every request of the stream")
        assertTrue(seen.all { it.method == "POST" && it.body.contentEquals(GooglevideoRequests.POST_BODY) }, "POST with x\\0")
        assertTrue(seen.all { it.userAgent == "TestAgent/1.0 (fake)" && it.rangeHeader == null }, "the stream set's headers, no Range header")
        assertTrue(seen.all { it.url.endsWith("&rn=${it.requestNumber}") }, "range and rn are the last parameters")
    }

    @Test
    fun aRequestStartingMidFileIsA206WithItsContentRange() {
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)

        val tail = get(url, "bytes=3000000-")
        assertEquals(206, tail.code)
        assertEquals("bytes 3000000-${total - 1}/$total", tail.headers["Content-Range"])
        assertEquals((total - 3_000_000).toString(), tail.headers["Content-Length"])
        assertContentEquals(file.copyOfRange(3_000_000, total), tail.body)
        assertEquals(listOf("3000000-5097151", "5097152-${total - 1}"), upstream.seen.map { it.rangeParameter })

        upstream.seen.clear()
        val middle = get(url, "bytes=100-199")
        assertEquals(206, middle.code)
        assertEquals("bytes 100-199/$total", middle.headers["Content-Range"])
        assertContentEquals(file.copyOfRange(100, 200), middle.body)
        assertEquals(listOf("100-199"), upstream.seen.map { it.rangeParameter }, "a closed range asks for no more than it needs")
    }

    @Test
    fun withoutAKnownLengthTheFirstAnswerGivesIt() {
        // A short file: the first piece comes back shorter than asked, so its Content-Length ends the file.
        upstream.size = 700_000
        val url = proxy.register(videoplayback(), headers)
        val answer = get(url)
        assertEquals(200, answer.code)
        assertEquals("700000", answer.headers["Content-Length"])
        assertContentEquals(file.copyOf(700_000), answer.body)
        assertEquals(listOf("0-2097151"), upstream.seen.map { it.rangeParameter })
    }

    @Test
    fun anUpstream403Answers403AndReportsTheTokenExpired() {
        upstream.expired = true
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)

        val answer = get(url, "bytes=0-")

        assertEquals(403, answer.code)
        assertEquals(listOf(proxy.token), expired.toList())
        assertEquals(url.split('/')[3], proxy.token)
    }

    @Test
    fun headGivesTheLengthWithoutABody() {
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)

        val head = get(url, method = "HEAD")
        assertEquals(200, head.code)
        assertEquals(total.toString(), head.headers["Content-Length"])
        assertEquals(0, head.body.size)

        val ranged = get(url, "bytes=1000-", method = "HEAD")
        assertEquals(206, ranged.code)
        assertEquals("bytes 1000-${total - 1}/$total", ranged.headers["Content-Range"])
        assertEquals((total - 1000).toString(), ranged.headers["Content-Length"])
        assertTrue(upstream.seen.isEmpty(), "a known length needs no upstream request")
    }

    @Test
    fun headLearnsAnUnknownLengthFromTheUpstream() {
        val url = proxy.register(videoplayback(), headers)
        upstream.useGet = true // the stream answers POST with 405, so the length comes from a Content-Range
        val head = get(url, method = "HEAD")
        assertEquals(200, head.code)
        assertEquals(total.toString(), head.headers["Content-Length"])
        assertEquals(0, head.body.size)
    }

    @Test
    fun twoReadersAtOnceEachGetTheirOwnBytes() {
        val video = proxy.register(videoplayback(itag = 137, clen = total.toLong()), headers)
        val audio = proxy.register(videoplayback(itag = 251, clen = total.toLong()), headers)
        assertTrue(video.endsWith("/0") && audio.endsWith("/1"), "$video $audio")
        var first: Answer? = null
        var second: Answer? = null
        val a = thread { first = get(video, "bytes=0-") }
        val b = thread { second = get(audio, "bytes=1234567-") }
        a.join(30_000)
        b.join(30_000)
        assertContentEquals(file, first!!.body)
        assertContentEquals(file.copyOfRange(1_234_567, total), second!!.body)
        val itags = upstream.seen.map { it.url.substringAfter("itag=").substringBefore('&') }.toSet()
        assertEquals(setOf("137", "251"), itags)
        val audioNumbers = upstream.seen.filter { "itag=251" in it.url }.map { it.requestNumber }
        assertEquals(listOf("0", "1"), audioNumbers, "each stream counts its own requests (two pieces from 1234567)")
    }

    @Test
    fun a405ToThePostSwitchesTheStreamToGetWithARangeHeader() {
        upstream.useGet = true
        val url = proxy.register(videoplayback(), headers)

        val answer = get(url, "bytes=2000000-")

        assertEquals(206, answer.code)
        assertEquals("bytes 2000000-${total - 1}/$total", answer.headers["Content-Range"], "the total came from the upstream's Content-Range")
        assertContentEquals(file.copyOfRange(2_000_000, total), answer.body)
        val seen = upstream.seen.toList()
        assertEquals("POST", seen.first().method, "the first request is a POST")
        val gets = seen.drop(1)
        assertTrue(gets.all { it.method == "GET" && it.rangeParameter == null }, "then GETs without the range parameter")
        assertEquals(listOf("bytes=2000000-4097151", "bytes=4097152-${total - 1}"), gets.map { it.rangeHeader })
        assertTrue(gets.all { it.userAgent == "TestAgent/1.0 (fake)" })

        upstream.seen.clear()
        get(url, "bytes=0-9")
        assertEquals(listOf("GET"), upstream.seen.map { it.method }, "the stream remembers the switch")
    }

    @Test
    fun anAddressThatIsNotGooglevideoIsReadWithARangeHeader() {
        val url = proxy.register("http://127.0.0.1:${server.port}/media/clip.mp4", emptyMap())
        val answer = get(url, "bytes=10-")
        assertEquals(206, answer.code)
        assertContentEquals(file.copyOfRange(10, total), answer.body)
        val seen = upstream.seen.toList()
        assertTrue(seen.all { it.method == "GET" && it.requestNumber == null && it.rangeParameter == null })
        assertEquals("bytes=10-2097161", seen.first().rangeHeader)
    }

    @Test
    fun unknownTokensAndStreamsAre404AndResetForgetsTheOldOnes() {
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)
        val base = url.substringBeforeLast('/').substringBeforeLast('/')
        assertEquals(404, get("$base/${"0".repeat(32)}/0").code)
        assertEquals(404, get(url.substringBeforeLast('/') + "/7").code)
        assertEquals(404, get("$base/").code)

        val old = proxy.token
        val fresh = proxy.reset()
        assertTrue(fresh != old && Regex("[0-9a-f]{32}").matches(fresh))
        assertEquals(404, get(url).code, "the old session's addresses are gone")
        val again = proxy.register(videoplayback(clen = total.toLong()), headers)
        assertTrue(again.endsWith("/$fresh/0"), again)
        assertEquals(206, get(again, "bytes=0-0").code)
    }

    @Test
    fun aRangePastTheEndIs416() {
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)
        val answer = get(url, "bytes=$total-")
        assertEquals(416, answer.code)
        assertEquals("bytes */$total", answer.headers["Content-Range"])
    }

    @Test
    fun closeStopsIt() {
        val url = proxy.register(videoplayback(clen = total.toLong()), headers)
        proxy.close()
        assertFailsWith<ConnectException> { get(url) }
        assertNull(expired.firstOrNull())
    }

    /** What the mock saw of one request. */
    private class Seen(val method: String, val url: String, val rangeHeader: String?, val body: ByteArray, val userAgent: String?) {
        val rangeParameter: String? get() = parameter("range")
        val requestNumber: String? get() = parameter("rn")
        private fun parameter(name: String): String? =
            url.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')
    }

    /** googlevideo as far as the proxy needs it. */
    private inner class Googlevideo : Dispatcher() {
        val seen = CopyOnWriteArrayList<Seen>()
        @Volatile var expired = false
        @Volatile var useGet = false
        @Volatile var size = total

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val method = request.method!!
            seen += Seen(method, url.toString(), request.getHeader("Range"), request.body.readByteArray(), request.getHeader("User-Agent"))
            if (expired) return MockResponse().setResponseCode(403)
            if (method == "POST") {
                if (useGet) return MockResponse().setResponseCode(405)
                val range = url.queryParameter("range") ?: return MockResponse().setResponseCode(400)
                val start = range.substringBefore('-').toInt()
                val end = minOf(range.substringAfter('-').toInt(), size - 1)
                if (start >= size) return MockResponse().setResponseCode(416)
                return slice(200, start, end, contentRange = false)
            }
            val header = request.getHeader("Range") ?: return slice(200, 0, size - 1, contentRange = false)
            val spec = header.removePrefix("bytes=")
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(size - 1) ?: (size - 1)
            if (start >= size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */$size")
            return slice(206, start, end, contentRange = true)
        }

        private fun slice(code: Int, start: Int, end: Int, contentRange: Boolean): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "video/mp4")
                .apply { if (contentRange) setHeader("Content-Range", "bytes $start-$end/$size") }
                .setBody(Buffer().write(file, start, end - start + 1))
    }
}
