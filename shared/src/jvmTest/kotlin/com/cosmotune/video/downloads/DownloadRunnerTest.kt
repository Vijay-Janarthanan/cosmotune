package com.cosmotune.video.downloads

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine end to end (plan task Y-24): the sample source's videos with their streams served by a local server (never the
 * network), files in a temporary folder, the join faked. One at a time, ranges, the join and its fallback, subtitles and
 * sidecar beside the file, pause and resume from the bytes on disk, cancel, Wi-Fi only, a restart.
 */
class DownloadRunnerTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val mib = 1024 * 1024
    private val video = ByteArray(3 * mib + 7) { (it % 241).toByte() }
    private val audio = ByteArray(mib + 3) { (it % 13 + 100).toByte() }
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val settings = FakeVideoSettingsRepository(VideoSettings())
    private val platform = TestPlatform()

    /** When set, the request it matches waits on [gate] (one request only). */
    @Volatile private var holdWhen: ((RecordedRequest) -> Boolean)? = null
    private val gate = CountDownLatch(1)

    private val ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00:01.000\" end=\"00:00:02.500\">Hello</p></div></body></tt>"

    @BeforeTest fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val hold = holdWhen
                if (hold != null && hold(request)) {
                    holdWhen = null
                    gate.await(15, TimeUnit.SECONDS)
                }
                val path = request.path.orEmpty()
                if (path.startsWith("/s/")) return MockResponse().setBody(ttml)
                val bytes = if (path.startsWith("/v/")) video else audio
                val (start, end) = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                val last = minOf(end, bytes.size - 1)
                return MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes $start-$last/${bytes.size}")
                    .setBody(Buffer().write(bytes.copyOfRange(start, last + 1)))
            }
        }
        server.start()
        dir = Files.createTempDirectory("y24-runner").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest fun stop() {
        gate.countDown()
        scope.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    // ---- The pieces ------------------------------------------------------------------------------------------------------

    /** The sample source, with one H.264 1080p30 stream, one AAC stream and an English TTML track served locally. */
    private inner class LocalSource(private val fake: FakeVideoSource = FakeVideoSource()) : VideoSource by fake {
        val streamCalls = AtomicInteger()

        override suspend fun details(videoId: String): VideoDetails = fake.details(videoId).copy(
            streams = streamsOf(videoId),
            subtitles = listOf(SubtitleTrack(server.url("/s/$videoId.ttml").toString(), "application/ttml+xml", "en", "English", autoGenerated = false)),
        )

        override suspend fun streams(videoId: String): StreamSet {
            streamCalls.incrementAndGet()
            return streamsOf(videoId)
        }

        private fun streamsOf(id: String) = StreamSet(
            videoOnly = listOf(VideoStream(
                server.url("/v/$id").toString(), 137, "video/mp4", VideoCodec.H264, "avc1.640028", 1920, 1080, 30, 3_800_000, false,
                video.size.toLong(), null, null, Delivery.DASH, false,
            )),
            muxed = emptyList(),
            audio = listOf(AudioStream(
                server.url("/a/$id").toString(), 140, "audio/mp4", AudioCodec.AAC, "mp4a.40.2", 128_000, 44_100, 2, audio.size.toLong(),
                null, null, Delivery.DASH, null, null, "en", AudioTrackKind.ORIGINAL,
            )),
            hlsUrl = null, dashMpdUrl = null, expiresAtMs = null, requestHeaders = mapOf("User-Agent" to "test-agent"), durationMs = 754_000,
        )
    }

    private inner class TestPlatform : DownloadPlatform {
        override val caps = DeviceCaps(1080, 60, setOf(VideoCodec.H264, VideoCodec.VP9))
        override val cacheDir: File get() = File(dir, "cache")
        override val stateDir: File get() = File(dir, "state")
        override fun outputDir(kind: DownloadKind, settings: VideoSettings) = File(dir, if (kind == DownloadKind.AUDIO) "Music" else "Movies")

        @Volatile var joinWorks = true
        val joins = AtomicInteger()
        override suspend fun join(video: File, audio: File, output: File, container: DownloadContainer): Boolean {
            joins.incrementAndGet()
            if (!joinWorks) return false
            output.writeBytes(video.readBytes() + audio.readBytes())
            return true
        }

        @Volatile var metered = false
        override fun isMetered() = metered
        override fun isBatteryLow() = false
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override fun freeBytes(settings: VideoSettings) = 21_000_000_000L
        override val appLanguage = "en"
        override fun languageName(tag: String) = if (tag == "en") "English" else tag
    }

    private fun runner(source: VideoSource = LocalSource(), scope: CoroutineScope = this.scope) =
        DownloadRunner(source, settings, scope, platform, FakeSegmentProvider())

    private fun request(quality: DownloadQuality = DownloadQuality.Rung(1080, 30), subtitles: Boolean = true) =
        DownloadRequest(quality, SubtitlePick(subtitles, if (subtitles) "en" else null))

    private fun item(id: String) = FakeVideoSource.sampleItem(id)!!

    private fun <T : Any> await(runner: DownloadRunner, what: String, pick: (List<DownloadEntry>) -> T?): T = runBlocking {
        withTimeoutOrNull(20_000) { runner.downloads.mapNotNull { pick(it) }.first() } ?: fail("timed out waiting for $what: ${runner.downloads.value}")
    }

    private fun done(runner: DownloadRunner, videoId: String): DownloadEntry =
        await(runner, "$videoId done") { list -> list.firstOrNull { it.item.id == videoId && it.state is DownloadState.Done } }

    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun rangesOf(path: String) = seen.filter { it.path == path }.map { it.getHeader("Range") }

    private val base = "Building a tiny synth from scratch [fakevideo01]"

    // ---- The tests -------------------------------------------------------------------------------------------------------

    @Test fun aVideoIsFetchedInRangesJoinedAndKeptWithItsSubtitlesAndSidecar() {
        val runner = runner()
        runner.enqueue(item("fakevideo01"), request())
        val entry = done(runner, "fakevideo01")
        val state = entry.state as DownloadState.Done

        val movie = File(dir, "Movies/$base.mp4")
        assertEquals(movie.path, state.path)
        assertContentEquals(video + audio, movie.readBytes(), "the joined file")
        assertEquals(movie.length(), state.bytes)
        assertEquals("1080p", entry.choice.label)
        assertEquals(137, entry.choice.video?.itag)

        // 2 MiB ranges with the stream set's headers, never a cookie
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-3145734"), rangesOf("/v/fakevideo01"))
        assertEquals(listOf("bytes=0-1048578"), rangesOf("/a/fakevideo01"))
        assertTrue(seen.filter { it.path!!.startsWith("/v/") || it.path!!.startsWith("/a/") }.all { it.getHeader("User-Agent") == "test-agent" && it.getHeader("Cookie") == null })

        // the subtitles and the sidecar beside the file
        val vtt = File(dir, "Movies/$base.en.vtt")
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\n", vtt.readText())
        assertEquals(listOf(vtt.path), entry.subtitleFiles)
        assertEquals("English", entry.subtitleGot)
        val sidecar = DownloadSidecar.decode(File(dir, "Movies/$base.cosmotune.json").readText())
        assertNotNull(sidecar)
        assertEquals("fakevideo01", sidecar.item.id)
        assertEquals(3, sidecar.chapters.size)
        assertEquals(4, sidecar.segments.size, "the sample segments of every category not switched off")
        assertEquals(listOf(SidecarSubtitle("$base.en.vtt", "en", "English", false)), sidecar.subtitles)
        assertEquals("1080p", sidecar.quality)
        assertEquals(listOf("$base.mp4"), sidecar.files)
        assertEquals("English", sidecar.languageGot)

        // the working files are gone and the list is kept
        assertTrue(File(dir, "cache").listFiles().orEmpty().isEmpty(), "${File(dir, "cache").list()?.toList()}")
        eventually("downloads.json holds the finished entry") { File(dir, "state/downloads.json").let { it.isFile && "\"done\"" in it.readText() } }
        assertEquals(movie.length() to 21_000_000_000L, runner.storage())
    }

    @Test fun aTemporarilyLockedFileDoesNotLoseTheFinalDownloadState() {
        val disk = FileDownloadsIo(platform.stateDir)
        val refused = AtomicInteger()
        val store = object : DownloadsFile.Io by disk {
            override fun rename(from: String, to: String) {
                if (to == DownloadsFile.NAME && "\"done\"" in disk.read(from).orEmpty() && refused.getAndIncrement() < 3) {
                    throw java.nio.file.AccessDeniedException(to)
                }
                disk.rename(from, to)
            }
        }
        val runner = DownloadRunner(LocalSource(), settings, scope, platform, FakeSegmentProvider(), store = store)
        runner.enqueue(item("fakevideo01"), request())
        done(runner, "fakevideo01")
        eventually("the final state is saved after a sharing violation") {
            DownloadsFile.decode(disk.read(DownloadsFile.NAME).orEmpty())?.entries?.singleOrNull()?.state is DownloadState.Done
        }
        assertTrue(refused.get() >= 4, "the failed replacements must have been retried")
    }

    @Test fun whenTheJoinFailsBothFilesAreKeptAndTheEntryPlaysFromThePair() {
        platform.joinWorks = false
        val runner = runner()
        runner.enqueue(item("fakevideo01"), request(subtitles = false))
        val entry = done(runner, "fakevideo01")
        val videoFile = File(dir, "Movies/$base.video.mp4")
        val audioFile = File(dir, "Movies/$base.audio.m4a")
        assertContentEquals(video, videoFile.readBytes())
        assertContentEquals(audio, audioFile.readBytes())
        assertEquals(DownloadState.Done(videoFile.path, video.size.toLong() + audio.size), entry.state)
        assertEquals(com.cosmotune.video.player.MediaInput.LocalFiles(null, videoFile.path, audioFile.path), entry.localFiles())
        assertEquals(listOf("$base.video.mp4", "$base.audio.m4a"), DownloadSidecar.decode(File(dir, "Movies/$base.cosmotune.json").readText())!!.files)
        assertEquals(null, entry.subtitleGot)
    }

    @Test fun audioOnlyIsWrittenAsItIsWithoutJoining() {
        val runner = runner()
        runner.enqueue(item("fakevideo02"), request(DownloadQuality.AudioOnly, subtitles = false))
        val entry = done(runner, "fakevideo02")
        val file = File(dir, "Music/Ten minutes of rain on a tin roof [fakevideo02].m4a")
        assertEquals(file.path, (entry.state as DownloadState.Done).path)
        assertContentEquals(audio, file.readBytes())
        assertEquals(DownloadKind.AUDIO, entry.kind)
        assertEquals(0, platform.joins.get())
        assertTrue(seen.none { it.path!!.startsWith("/v/") })
    }

    @Test fun pauseKeepsTheBytesAndResumeGoesOnFromThem() {
        holdWhen = { it.path == "/v/fakevideo01" && it.getHeader("Range") == "bytes=2097152-3145734" }
        val runner = runner()
        runner.enqueue(item("fakevideo01"), request(subtitles = false))
        eventually("the second range is asked") { rangesOf("/v/fakevideo01").size == 2 }
        val id = runner.downloads.value.single().id
        runner.pause(id)
        assertEquals(DownloadState.Paused(PauseReason.USER), runner.downloads.value.single().state)
        eventually("the first range is on disk") { File(dir, "cache/$id.video.part").length() == 2L * mib }
        gate.countDown()
        Thread.sleep(200)
        assertEquals(DownloadState.Paused(PauseReason.USER), runner.downloads.value.single().state, "a paused download stays paused")

        runner.resume(id)
        done(runner, "fakevideo01")
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-3145734", "bytes=2097152-3145734"), rangesOf("/v/fakevideo01"), "resumed from the bytes on disk")
        assertContentEquals(video + audio, File(dir, "Movies/$base.mp4").readBytes())
    }

    @Test fun cancelDeletesTheWorkingFilesOrTheFinishedOnes() {
        holdWhen = { it.path == "/v/fakevideo02" && it.getHeader("Range") == "bytes=2097152-3145734" }
        val runner = runner()
        runner.enqueue(item("fakevideo02"), request(subtitles = false))
        eventually("the second range is asked") { rangesOf("/v/fakevideo02").size == 2 }
        val running = runner.downloads.value.single().id
        runner.cancel(running)
        assertTrue(runner.downloads.value.isEmpty())
        gate.countDown()
        eventually("the working file is deleted") { !File(dir, "cache/$running.video.part").exists() }

        runner.enqueue(item("fakevideo01"), request())
        val entry = done(runner, "fakevideo01")
        val files = listOf("Movies/$base.mp4", "Movies/$base.en.vtt", "Movies/$base.cosmotune.json").map { File(dir, it) }
        assertTrue(files.all { it.isFile })
        runner.cancel(entry.id)
        eventually("every file of the download is deleted") { files.none { it.exists() } }
        assertTrue(runner.downloads.value.isEmpty())
    }

    @Test fun onlyOnWifiHoldsTheQueueOnMobileData() {
        platform.metered = true
        val runner = runner()
        runner.enqueue(item("fakevideo01"), request(subtitles = false))
        assertEquals(DownloadState.WaitingForWifi, runner.downloads.value.single().state)
        Thread.sleep(300)
        assertEquals(0, seen.size, "nothing is fetched on mobile data")
        platform.metered = false
        platform.changes.tryEmit(Unit)
        done(runner, "fakevideo01")
    }

    @Test fun aRestartedEngineResumesFromItsList() {
        holdWhen = { it.path == "/v/fakevideo01" && it.getHeader("Range") == "bytes=2097152-3145734" }
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val before = runner(scope = first)
        before.enqueue(item("fakevideo01"), request(subtitles = false))
        eventually("the second range is asked") { rangesOf("/v/fakevideo01").size == 2 }
        eventually("the list says it runs") { File(dir, "state/downloads.json").let { it.isFile && "\"running\"" in it.readText() } }
        // the app is killed
        first.cancel()
        runBlocking { first.coroutineContext.job.join() }
        gate.countDown()

        val after = runner()
        done(after, "fakevideo01")
        assertEquals(listOf("bytes=0-2097151", "bytes=2097152-3145734", "bytes=2097152-3145734"), rangesOf("/v/fakevideo01"))
        assertContentEquals(video + audio, File(dir, "Movies/$base.mp4").readBytes())
        assertEquals(1, after.downloads.value.size)
    }

    @Test fun aBatchRunsOneAtATimeAndSkipsWhatIsQueued() {
        val runner = runner()
        runner.enqueue(item("fakeplvideo01"), request(DownloadQuality.Auto, subtitles = false))
        val info = DownloadBatchInfo("pl:${FakeVideoSource.PLAYLIST_ID}", BatchKind.PLAYLIST, "Thirty for the road")
        runner.enqueueBatch(info, listOf("fakeplvideo01", "fakeplvideo02", "fakeplvideo03").map(::item), request(DownloadQuality.Auto, subtitles = false))
        assertEquals(BatchState(info, total = 2, done = 0, paused = false), runner.batches.value.single())
        await(runner, "every download done") { list -> list.takeIf { l -> l.size == 3 && l.all { it.state is DownloadState.Done } } }
        assertEquals(BatchState(info, total = 2, done = 2, paused = false), runner.batches.value.single())
        // one at a time: the requests of one video never interleave with another's
        val order = seen.map { it.path!!.substringAfterLast('/') }
        assertEquals(listOf("fakeplvideo01", "fakeplvideo02", "fakeplvideo03"), order.distinct())
        assertEquals(order.distinct().size, order.zipWithNext().count { (a, b) -> a != b } + 1)
        assertTrue(seen.none { it.path!!.startsWith("/s/") }, "no subtitles asked")
        assertFalse(File(dir, "Movies").listFiles().orEmpty().any { it.name.endsWith(".vtt") })
    }
}
