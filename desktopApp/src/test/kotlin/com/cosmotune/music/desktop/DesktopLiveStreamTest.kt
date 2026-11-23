package com.cosmotune.music.desktop

import com.cosmotune.podio.PodioLiveTitle
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A station read by the app itself ([DesktopLiveStream]) against a station on this computer: the titles come out of the audio,
 * a dropped connection is opened again, a station that moved is followed, and one the reader cannot open still plays through
 * FFmpeg's own reader. [DesktopLiveStationsLiveTest] plays real stations the same way.
 */
class DesktopLiveStreamTest {
    private val servers = mutableListOf<HttpServer>()
    private val tone: ByteArray = requireNotNull(javaClass.getResourceAsStream("/podio/tone.mp3")).readBytes()

    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    @Test fun `a station that hangs up is opened again and its titles are taken out of the audio`() {
        val half = tone.copyOf(tone.size / 2)
        val requests = AtomicInteger()
        val url = serve { path, exchange ->
            require(path == "/live")
            when (requests.incrementAndGet()) {
                1 -> exchange.icy(half, "Tone One")
                2 -> exchange.icy(tone, "Tone Two")
                else -> { exchange.sendResponseHeaders(503, -1); exchange.close() }
            }
        }
        val titles = mutableListOf<String>()
        val stream = DesktopLiveStream(url, retryDelaysMs = listOf(10L, 10L), onTitle = { titles += it })
        stream.open()
        val audio = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        val failure = assertFailsWith<DesktopStreamException> {
            while (true) { val n = stream.read(buffer, buffer.size); if (n < 0) break; audio.write(buffer, 0, n) }
        }
        assertTrue(failure.network, "a station that stops answering is a network failure, so the player waits it out")
        assertEquals(1, stream.reconnects)
        assertContentEquals(half + tone, audio.toByteArray(), "the audio, without a byte of the titles, across the drop")
        assertEquals(listOf("Tone One", "Tone Two"), titles.distinct())
        assertFalse(DesktopLiveStream.suits(url), "a station that failed this early goes to FFmpeg's reader next time")
        stream.close()
    }

    @Test fun `a station that moved is followed`() {
        val url = serve { path, exchange ->
            if (path == "/old") { exchange.responseHeaders.add("Location", "/live"); exchange.sendResponseHeaders(302, -1); exchange.close() }
            else exchange.icy(tone, "Moved")
        }.replace("/live", "/old")
        val stream = DesktopLiveStream(url, retryDelaysMs = emptyList(), onTitle = {})
        stream.open()
        val buffer = ByteArray(8_192)
        val audio = ByteArrayOutputStream()
        while (audio.size() < 8_192) { val n = stream.read(buffer, buffer.size); if (n < 0) error("ended"); audio.write(buffer, 0, n) }
        stream.close()
        assertContentEquals(tone.copyOf(audio.size()), audio.toByteArray())
    }

    @Test fun `a playlist or a page is left to FFmpeg's reader`() {
        val url = serve { _, exchange ->
            val body = "http://example.invalid/stream\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "audio/x-mpegurl"); exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertFailsWith<DesktopStreamException> { DesktopLiveStream(url, onTitle = {}).open() }
        assertTrue(DesktopLiveStream.audioType("audio/mpeg") && DesktopLiveStream.audioType("audio/aacp") && DesktopLiveStream.audioType(null))
        assertFalse(DesktopLiveStream.audioType("text/html; charset=utf-8") || DesktopLiveStream.audioType("audio/x-scpls"))
        assertFalse(DesktopLiveStream.suits("https://radio.example/live.m3u8") || DesktopLiveStream.suits("https://radio.example/a.pls?x=1"))
    }

    @Test fun `the player decodes a station it reads itself, across a dropped connection, and hears its titles`() {
        val requests = AtomicInteger()
        val url = serve { _, exchange ->
            when (requests.incrementAndGet()) {
                1 -> exchange.icy(tone.copyOf(tone.size / 2), "First Song")
                2 -> exchange.icy(tone, "Second Song")
                else -> { exchange.sendResponseHeaders(503, -1); exchange.close() }
            }
        }
        val decoder = DesktopAudioDecoder()
        decoder.open(url, requested = DesktopPcmFormat(44_100, 2, 2), live = true).getOrThrow()
        try {
            assertTrue(decoder.readsItself)
            var bytes = 0L
            var peak = 0
            while (true) {
                val block = decoder.read() ?: break
                bytes += block.size
                for (i in 0 until block.size / 2 step 64) peak = maxOf(peak, abs(((block[2 * i + 1].toInt() shl 8) or (block[2 * i].toInt() and 0xFF)).toShort().toInt()))
            }
            val seconds = bytes / (44_100.0 * 4)
            // Four seconds of tone, then two more after the drop: a little lost at the seam at most.
            assertTrue(seconds > 5.5, "decoded $seconds s")
            assertTrue(peak > 1_500, "peak $peak is not the tone")
            assertEquals("Second Song", PodioLiveTitle.recent(url))
        } finally { decoder.close() }
    }

    @Test fun `an old SHOUTcast server is read by the app too`() {
        // "ICY 200 OK" instead of an HTTP status line; it answers once, then is gone.
        val socket = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            runCatching { socket.accept() }.getOrNull()?.use {
                val reader = it.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val out = it.getOutputStream()
                out.write("ICY 200 OK\r\ncontent-type: audio/mpeg\r\nicy-name: Old\r\n\r\n".toByteArray())
                runCatching { out.write(tone); out.flush() }
            }
            socket.close()
        }
        val url = "http://127.0.0.1:${socket.localPort}/old-shoutcast"
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(url, requested = DesktopPcmFormat(44_100, 2, 2), live = true).getOrThrow()
            assertTrue(decoder.readsItself)
            var bytes = 0L
            while (true) bytes += (decoder.read() ?: break).size
            assertTrue(bytes / (44_100.0 * 4) > 3.0, "decoded ${bytes / (44_100.0 * 4)} s")
        } finally { decoder.close(); socket.close(); worker.join(1_000) }
    }

    @Test fun `a station the app's reader cannot open still plays through FFmpeg's`() {
        // Audio sent as a text page: the app's reader turns it away, FFmpeg's looks at the bytes and plays them.
        val url = serve { _, exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, tone.size.toLong())
            exchange.responseBody.use { it.write(tone) }
        }
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(url, requested = DesktopPcmFormat(44_100, 2, 2), live = true).getOrThrow()
            assertFalse(decoder.readsItself)
            var bytes = 0L
            while (true) bytes += (decoder.read() ?: break).size
            assertTrue(bytes / (44_100.0 * 4) > 3.0, "decoded ${bytes / (44_100.0 * 4)} s")
            assertFalse(DesktopLiveStream.suits(url), "and it goes straight to FFmpeg's reader next time")
        } finally { decoder.close() }
    }

    private fun serve(handler: (String, com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> runCatching { handler(exchange.requestURI.path, exchange) }; runCatching { exchange.close() } }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/live"
    }

    /** [audio] as an Icecast station sends it when asked for titles: a title block every [INTERVAL] bytes. */
    private fun com.sun.net.httpserver.HttpExchange.icy(audio: ByteArray, title: String) {
        val asked = requestHeaders.getFirst("Icy-MetaData") == "1"
        responseHeaders.add("Content-Type", "audio/mpeg")
        if (asked) responseHeaders.add("icy-metaint", INTERVAL.toString())
        sendResponseHeaders(200, 0)
        responseBody.use { out ->
            var at = 0
            while (at < audio.size) {
                val take = minOf(INTERVAL, audio.size - at)
                out.write(audio, at, take); at += take
                if (asked && take == INTERVAL) {
                    val text = "StreamTitle='$title';".toByteArray()
                    val blocks = (text.size + 15) / 16
                    out.write(blocks); out.write(text.copyOf(blocks * 16))
                }
            }
        }
    }

    private companion object { const val INTERVAL = 4_000 }
}
