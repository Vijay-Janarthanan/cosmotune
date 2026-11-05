package com.cosmotune.video.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fetching in 2 MiB ranges (plan task Y-24, step 4), against a local server: never the network. */
class RangeDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val mib = 1024 * 1024
    private val content = ByteArray(5 * mib + 100) { (it % 251).toByte() }
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeTest fun start() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("y24-range").toFile()
    }

    @AfterTest fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** Serves [bytes] by `Range` header (206 with `Content-Range`) or by `range=` parameter (200); [answer] can step in first. */
    private fun serve(bytes: ByteArray = content, answer: (RecordedRequest) -> MockResponse? = { null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                answer(request)?.let { return it }
                val header = request.getHeader("Range")
                val parameter = request.path.orEmpty().substringAfter("range=", "").substringBefore('&')
                val (start, end) = (header?.removePrefix("bytes=") ?: parameter.ifEmpty { "0-${bytes.size - 1}" }).split('-').map { it.toInt() }
                if (start >= bytes.size) return MockResponse().setResponseCode(416)
                val last = minOf(end, bytes.size - 1)
                val response = MockResponse().setBody(Buffer().write(bytes.copyOfRange(start, last + 1)))
                return if (header != null) response.setResponseCode(206).addHeader("Content-Range", "bytes $start-$last/${bytes.size}") else response
            }
        }
    }

    private fun ranges() = seen.map { it.getHeader("Range") }

    @Test fun aStreamIsFetchedInRangesOfTwoMebibytes() = runBlocking<Unit> {
        serve()
        val file = File(dir, "v.part")
        val progress = mutableListOf<Long>()
        val length = RangeDownloader().download(server.url("/v").toString(), mapOf("User-Agent" to "test"), file, expectedBytes = content.size.toLong()) { w, _ -> progress += w }
        assertEquals(content.size.toLong(), length)
        assertContentEquals(content, file.readBytes())
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-4194303", "bytes=4194304-5242979"), ranges())
        assertTrue(seen.all { it.method == "GET" && it.getHeader("User-Agent") == "test" && it.getHeader("Cookie") == null })
        assertEquals(listOf(0L, 2_097_152L, 4_194_304L, 5_242_980L), progress)
    }

    @Test fun withoutAKnownLengthTheServersTotalEndsIt() = runBlocking<Unit> {
        serve()
        val file = File(dir, "v.part")
        RangeDownloader().download(server.url("/v").toString(), emptyMap(), file)
        assertContentEquals(content, file.readBytes())
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-4194303", "bytes=4194304-5242979"), ranges())
    }

    @Test fun aDownloadResumesFromTheBytesOnDisk() = runBlocking<Unit> {
        serve()
        val file = File(dir, "v.part").apply { writeBytes(content.copyOfRange(0, 1_000_000)) }
        RangeDownloader().download(server.url("/v").toString(), emptyMap(), file, content.size.toLong())
        assertContentEquals(content, file.readBytes())
        assertEquals("bytes=1000000-3097151", ranges().first())
        // a file already complete asks nothing
        seen.clear()
        assertEquals(content.size.toLong(), RangeDownloader().download(server.url("/v").toString(), emptyMap(), file, content.size.toLong()))
        assertTrue(seen.isEmpty())
    }

    @Test fun aForbiddenRangeAsksForAFreshAddressOnceAndGoesOn() = runBlocking<Unit> {
        serve { if (it.path.orEmpty().startsWith("/old") && seen.size > 1) MockResponse().setResponseCode(403) else null }
        val file = File(dir, "v.part")
        var refreshes = 0
        RangeDownloader().download(server.url("/old/v").toString(), emptyMap(), file, content.size.toLong(), refresh = {
            refreshes++
            server.url("/new/v").toString()
        })
        assertEquals(1, refreshes)
        assertContentEquals(content, file.readBytes())
        assertEquals(listOf("/old/v", "/old/v", "/new/v", "/new/v"), seen.map { it.path })
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-4194303", "bytes=2097152-4194303", "bytes=4194304-5242979"), ranges(), "it goes on from the bytes it has")
    }

    @Test fun aSecondForbiddenRightAfterTheRefreshIsSourceExpired() = runBlocking<Unit> {
        serve { MockResponse().setResponseCode(403) }
        var refreshes = 0
        assertFailsWith<SourceExpired> {
            RangeDownloader().download(server.url("/v").toString(), emptyMap(), File(dir, "v.part"), content.size.toLong(), refresh = {
                refreshes++
                server.url("/fresh").toString()
            })
        }
        assertEquals(1, refreshes)
        assertFailsWith<SourceExpired> { RangeDownloader().download(server.url("/v").toString(), emptyMap(), File(dir, "w.part"), null) }
    }

    @Test fun googlevideoAddressesCarryTheRangeAndTheRequestNumberAndPostTheBody() = runBlocking<Unit> {
        serve()
        val file = File(dir, "v.part")
        RangeDownloader(isGooglevideo = { true }).download(server.url("/videoplayback?id=x&itag=137&range=9-9").toString(), emptyMap(), file, content.size.toLong())
        assertContentEquals(content, file.readBytes())
        assertEquals(
            listOf("/videoplayback?id=x&itag=137&range=0-2097151&rn=1", "/videoplayback?id=x&itag=137&range=2097152-4194303&rn=2", "/videoplayback?id=x&itag=137&range=4194304-5242979&rn=3"),
            seen.map { it.path },
        )
        assertTrue(seen.all { it.method == "POST" && it.getHeader("Range") == null })
        assertContentEquals(byteArrayOf(0x78, 0x00), seen.first().body.readByteArray())
    }

    @Test fun aShortRangeEndsAStreamOfUnknownLength() = runBlocking<Unit> {
        val three = content.copyOfRange(0, 3 * mib)
        serve(three)
        val file = File(dir, "v.part")
        RangeDownloader(isGooglevideo = { true }).download(server.url("/videoplayback?id=x").toString(), emptyMap(), file)
        assertContentEquals(three, file.readBytes())
        assertEquals(2, seen.size)
    }

    @Test fun aFailedRangeIsTriedAgain() = runBlocking<Unit> {
        var failures = 1
        serve { if (failures-- > 0) MockResponse().setResponseCode(500) else null }
        val file = File(dir, "v.part")
        RangeDownloader(retryDelayMs = 0).download(server.url("/v").toString(), emptyMap(), file, content.size.toLong())
        assertContentEquals(content, file.readBytes())
        assertEquals(4, seen.size)
        serve { MockResponse().setResponseCode(500) }
        assertFailsWith<java.io.IOException> { RangeDownloader(retryDelayMs = 0).download(server.url("/v").toString(), emptyMap(), File(dir, "w.part"), 10) }
    }

    @Test fun cancellingStopsTheDownloadAndKeepsTheBytesFetched() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        serve { if (seen.size == 2) { gate.await(10, TimeUnit.SECONDS); null } else null }
        val file = File(dir, "v.part")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = scope.launch { RangeDownloader().download(server.url("/v").toString(), emptyMap(), file, content.size.toLong()) }
            withTimeout(10_000) { while (seen.size < 2) kotlinx.coroutines.delay(20) }
            withTimeout(10_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(2L * mib, file.length(), "the first range stays for a resume")
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }
}
