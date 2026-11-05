package com.cosmotune.music.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopManifestTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { Thread(it, "manifest-fixture").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { request -> try { handler(request) } finally { request.close() } }; start()
        }
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun HttpExchange.reply(bytes: ByteArray) { sendResponseHeaders(200, bytes.size.toLong()); responseBody.use { it.write(bytes) } }
    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray = java.nio.ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    private fun fields(vararg values: Int) = java.nio.ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private val init get() = box("ftyp", "iso6".toByteArray()) + box("moov",
        box("trak", box("tkhd", fields(0, 0, 0, 1)) + box("mdia", box("mdhd", fields(0, 0, 0, 48_000)))) +
            box("mvex", box("trex", fields(0, 1, 1, 48_000, 1024 * 1024, 0))))
    private fun segment(time: Int) = box("moof", box("traf", box("tfhd", fields(0x020000, 1)) + box("tfdt", fields(0, time)) + box("trun", fields(0, 1)))) + box("mdat", ByteArray(1024 * 1024) { time.toByte() })

    @Test fun seekIndexMatchesTheByteArrayReferenceWhilePayloadCopiesStaySmall() {
        val segments = listOf(segment(0), segment(48_000), segment(96_000))
        val expected = DesktopManifestDownload.Mp4.withSidx(init + segments.reduce(ByteArray::plus))
        val output = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) { assertTrue(length <= 32 * 1024); output.write(bytes, offset, length) }
        }
        val folder = temporary.newFolder()
        DesktopManifestDownload.assemble(folder, "m4a", init, segments.asSequence(), sink)
        assertContentEquals(expected, output.toByteArray())
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }

    @Test fun segmentAndRedirectRequestsNeverForwardSourceCredentialsToAnotherOrigin() = runBlocking<Unit> {
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        Server { request ->
            assertNull(request.requestHeaders.getFirst("X-Source-Key")); assertNull(request.requestHeaders.getFirst("Authorization"))
            received += request.requestURI.path
            request.reply(if (request.requestURI.path == "/init") init else segment(0))
        }.use { foreign ->
            Server { request ->
                assertEquals("private", request.requestHeaders.getFirst("X-Source-Key"))
                if (request.requestURI.path == "/list.m3u8") request.reply(("#EXTM3U\n#EXT-X-MAP:URI=\"${foreign.url("/init")}\"\n${"/redirect"}\n#EXT-X-ENDLIST\n").toByteArray())
                else { request.responseHeaders.add("Location", foreign.url("/segment")); request.sendResponseHeaders(302, -1) }
            }.use { origin ->
                val output = ByteArrayOutputStream()
                DesktopManifestDownload.save(DesktopStream(origin.url("/list.m3u8"), headers = mapOf("X-Source-Key" to "private", "Authorization" to "Bearer private")),
                    "m4a", output, temporary.newFolder(), "fixture") { _, _ -> }
                assertEquals(listOf("/init", "/segment"), received.toList())
                assertTrue(output.size() > 1024 * 1024)
            }
        }
    }

    @Test fun chunkedOversizedManifestCannotAllocateAnUnboundedBody() = runBlocking<Unit> {
        Server { request ->
            request.sendResponseHeaders(200, 0)
            request.responseBody.write(ByteArray(DesktopManifestDownload.MAX_MANIFEST_BYTES + 1) { 65 })
        }.use { server ->
            val output = ByteArrayOutputStream()
            assertFailsWith<IllegalArgumentException> { DesktopManifestDownload.save(DesktopStream(server.url("/list.m3u8")), "m4a", output, temporary.newFolder(), "fixture") { _, _ -> } }
            assertEquals(0, output.size())
        }
    }

    @Test fun cancellationClosesABlockedSegmentReadBeforeReturningAndRemovesAssemblyFiles() = runBlocking<Unit> {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server { request -> when (request.requestURI.path) {
            "/list.m3u8" -> request.reply("#EXTM3U\n#EXT-X-MAP:URI=\"/init\"\n/segment\n#EXT-X-ENDLIST\n".toByteArray())
            "/init" -> request.reply(init)
            else -> { request.sendResponseHeaders(200, 1024 * 1024); request.responseBody.write(byteArrayOf(0)); request.responseBody.flush(); blocked.countDown(); release.await(10, TimeUnit.SECONDS) }
        } }.use { server ->
            val folder = temporary.newFolder()
            val task = launch(Dispatchers.IO) { DesktopManifestDownload.save(DesktopStream(server.url("/list.m3u8")), "m4a", ByteArrayOutputStream(), folder, "fixture") { _, _ -> } }
            try {
                assertTrue(withContext(Dispatchers.IO) { blocked.await(5, TimeUnit.SECONDS) })
                withTimeout(2_000) { task.cancelAndJoin() }
                assertTrue(folder.listFiles().orEmpty().isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun malformedAndEncryptedLayoutsAreRejectedAndTemporaryFilesAreRemoved() {
        for (repeat in listOf("-1", "2147483647", "9223372036854775807", "not-a-number")) assertFails {
            DesktopManifestDownload.parseDash("""<MPD><SegmentTemplate initialization="i" media="${'$'}Number${'$'}"><SegmentTimeline><S d="1" r="$repeat"/></SegmentTimeline></SegmentTemplate></MPD>""")
        }
        for (extra in listOf("#EXT-X-KEY:METHOD=NONE\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-BYTERANGE:10@0", "#EXT-X-DISCONTINUITY")) assertFails {
            DesktopManifestDownload.hlsSegments("#EXTM3U\n#EXT-X-MAP:URI=\"init\"\n$extra\nsegment\n#EXT-X-ENDLIST\n")
        }
        val folder = temporary.newFolder()
        assertFails { DesktopManifestDownload.assemble(folder, "m4a", init, sequenceOf(byteArrayOf(1, 2, 3)), ByteArrayOutputStream()) }
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
}
