package com.cosmotune.music.data.cast.dlna

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DlnaMediaRelayTest {
    private val payload = ByteArray(200_000) { (it * 17 % 253).toByte() }
    private val seen = Collections.synchronizedList(ArrayList<Pair<String?, String?>>())

    private fun upstream(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/video") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            seen += exchange.requestHeaders.getFirst("User-Agent") to range
            if (exchange.requestHeaders.getFirst("User-Agent") != "TvOnly/1") { exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext }
            val (from, to) = DlnaMediaRelay.parseRange(range, payload.size.toLong()) ?: (0L to payload.size - 1L)
            exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
            exchange.sendResponseHeaders(206, to - from + 1)
            exchange.responseBody.use { it.write(payload, from.toInt(), (to - from + 1).toInt()) }
        }
        server.start()
        return server
    }

    private fun get(url: String, range: String? = null, method: String = "GET"): Triple<Int, ByteArray, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val body = if (code < 400 && method == "GET") c.inputStream.use { it.readBytes() } else ByteArray(0)
        val length = c.getHeaderField("Content-Length")
        c.disconnect()
        return Triple(code, body, length)
    }

    @Test fun theTvGetsRangesWithTheHeadersTheUpstreamNeeds() {
        val up = upstream()
        val relay = DlnaMediaRelay(windowBytes = 32 * 1024)
        try {
            val url = relay.publish(DlnaMediaRelay.Source.Remote("http://127.0.0.1:${up.address.port}/video", mapOf("User-Agent" to "TvOnly/1"), "video/mp4"), "127.0.0.1")
            val part = get(url, "bytes=50000-99999")
            assertEquals(206, part.first)
            assertContentEquals(payload.copyOfRange(50_000, 100_000), part.second)
            assertTrue(seen.all { it.first == "TvOnly/1" && it.second != null }, "every upstream request carries the headers and a range")
            val tail = get(url, "bytes=-1000")
            assertContentEquals(payload.copyOfRange(199_000, 200_000), tail.second)
            assertEquals("200000", get(url, method = "HEAD").third)
            assertEquals(416, get(url, "bytes=999999-").first)
        } finally { relay.close(); up.stop(0) }
    }

    @Test fun onlyAnAddressThatWasHandedOutIsServedAndEachCastGetsItsOwn() {
        val up = upstream()
        val relay = DlnaMediaRelay()
        try {
            val source = DlnaMediaRelay.Source.Remote("http://127.0.0.1:${up.address.port}/video", mapOf("User-Agent" to "TvOnly/1"), "video/mp4")
            val first = relay.publish(source, "127.0.0.1")
            val second = relay.publish(source, "127.0.0.1")
            assertNotEquals(first, second, "a random token per session")
            assertEquals(404, get(first.substringBeforeLast('/') + "/guess").first)
            assertEquals(404, get(first.substringBeforeLast("/m/") + "/").first)
            relay.revoke(first)
            assertEquals(404, get(first).first)
            assertEquals(200, get(second, "bytes=0-9").first.let { if (it == 206) 200 else it })
        } finally { relay.close(); up.stop(0) }
    }

    @Test fun theServerIsBoundToTheNetworkAddressAndStopsWhenTheCastEnds() {
        val relay = DlnaMediaRelay()
        val url = relay.publish(DlnaMediaRelay.Source.Remote("http://127.0.0.1:1/x", emptyMap(), "video/mp4"), "127.0.0.1")
        assertTrue(url.startsWith("http://127.0.0.1:"))
        relay.close()
        val gone = runCatching { get(url).first }.isFailure
        assertTrue(gone, "nothing answers after close")
    }
}
