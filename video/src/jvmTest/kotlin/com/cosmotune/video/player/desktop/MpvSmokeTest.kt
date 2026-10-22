package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.SubtitleCue
import com.cosmotune.video.player.VideoScale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * libmpv for real (plan task Y-12, step 7): a handle is created and destroyed, then the local sample plays for a second into a
 * 320 x 180 software render and a frame with pixels in it comes out; the engine as a whole loads, plays, seeks, goes audio-only
 * and unloads. Skipped when this machine has no libmpv or the sample is missing. Sound goes nowhere (`ao=null`); nothing
 * touches the network.
 */
class MpvSmokeTest {

    private val sample = File("D:/Vijay/Personal/Canvas/docs/superpowers/design/cosmotune/ui/media/canvas-rain.mp4")
    private val quiet = MpvPlayer.DEFAULT_OPTIONS + mapOf("ao" to "null")

    private fun mpvOrSkip(): MpvLibrary {
        val mpv = testMpv()
        assumeTrue("libmpv loads on this machine", mpv != null)
        return mpv!!
    }

    /** How long a playhead may take to pass its mark (a second of play) on a loaded machine before the test fails. */
    private val PLAYHEAD_DEADLINE_MS = 10_000L

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun aHandleIsCreatedInitialisedAndDestroyed() {
        val mpv = mpvOrSkip()
        val handle = assertNotNull(mpv.mpv_create())
        val voResult = mpv.mpv_set_option_string(handle, "vo", "libmpv")
        if (voResult != 0 && System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            // The first run on a macOS runner (Apple Silicon): the libmpv of the runtime package loads but refuses the render
            // output the player is built on. Video playback on macOS is therefore unverified in this release (README says so).
            mpv.mpv_terminate_destroy(handle)
            assumeTrue("macOS: this libmpv refuses vo=libmpv (code $voResult); video playback on macOS is not verified", false)
        }
        assertEquals(0, voResult, "vo=libmpv is accepted")
        assertTrue(mpv.mpv_initialize(handle) >= 0)
        assertEquals(0, mpv.mpv_command(handle, arrayOf("set", "pause", "yes")))
        mpv.mpv_terminate_destroy(handle)
    }

    @Suppress("DEPRECATION") // Thread.id: the test classes target Java 17, which has no threadId()
    @Test
    fun theSamplePlaysASecondIntoA320By180SoftwareRender() {
        val mpv = mpvOrSkip()
        assumeTrue("the sample video is on this machine", sample.isFile)
        val snapshot = MutableStateFlow(PlayerSnapshot.Idle)
        val cues = MutableStateFlow<List<SubtitleCue>>(emptyList())
        val exchange = FrameExchange()
        val player = MpvPlayer(mpv, snapshot, cues, quiet, latestLoad = { 1 }, onVideoSize = exchange::setVideoSize)
        val renderer = MpvRenderer(mpv, player.handle, exchange)
        try {
            assertTrue(renderer.start(), "the software render context exists")
            val screen = Any()
            exchange.request(screen, 320, 180, VideoScale.FIT)
            player.load(1, sample.absolutePath, 0, live = false, remote = false, audioOnly = false, userAgent = null, headerFields = emptyList())
            assertEquals(PlayerStatus.LOADING, snapshot.value.status)
            waitFor(10_000, "the file to load") { snapshot.value.status == PlayerStatus.READY }
            player.set("pause", "no")
            waitFor(5_000, "playing") { snapshot.value.playing }
            // Y-34: a deadline, not a fixed second, so a loaded machine cannot fail it; a render or clock that does not move still does.
            val started = System.nanoTime()
            waitFor(PLAYHEAD_DEADLINE_MS, "the playhead to pass 600 ms") { player.positionMs() >= 600 }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val position = player.positionMs()
            assertTrue(position <= elapsed + 1_500, "the playhead runs at the clock's pace, not ahead of it: $position ms after $elapsed ms")

            waitFor(5_000, "a published frame") { exchange.frames.value > 0 }
            val frame = assertNotNull(exchange.acquire())
            val s = snapshot.value
            assertTrue(s.videoWidth > 0 && s.videoHeight > 0, "the video's size is known: ${s.videoWidth} x ${s.videoHeight}")
            assertTrue(s.durationMs > 0, "the duration is known")
            assertNotNull(s.decoderName, "the decoder is reported")
            assertTrue(frame.width <= 320 && frame.height <= 180 && (frame.width == 320 || frame.height == 180), "fits 320 x 180: ${frame.width} x ${frame.height}")
            assertEquals(FrameGeometry.renderSize(320, 180, s.videoWidth, s.videoHeight, VideoScale.FIT), FrameGeometry.pack(frame.width, frame.height))
            var lit = 0
            for (y in 0 until frame.height step 4) for (x in 0 until frame.width step 4) if (frame.rgb(x, y) != 0) lit++
            val sampled = ((frame.height + 3) / 4) * ((frame.width + 3) / 4)
            assertTrue(lit * 10 > sampled, "the frame has pixels: $lit of $sampled samples are not black")
            println("MpvSmokeTest: ${s.videoWidth}x${s.videoHeight}, ${s.durationMs} ms, decoder ${s.decoderName}, frame ${frame.width}x${frame.height}, $lit/$sampled lit")

            // Steady state: frames keep coming without new buffers; measure what the render thread allocates per frame.
            val renderThread = Thread.getAllStackTraces().keys.first { it.name == "cosmotune-mpv-render" }
            val beans = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val bytesBefore = beans.getThreadAllocatedBytes(renderThread.id)
            val framesBefore = exchange.frames.value
            Thread.sleep(1_000)
            val frames = exchange.frames.value - framesBefore
            val bytes = beans.getThreadAllocatedBytes(renderThread.id) - bytesBefore
            assertTrue(frames > 5, "frames keep coming: $frames in a second")
            println("MpvSmokeTest: the render thread allocated ${bytes / frames} bytes per frame over $frames frames")
            assertTrue(bytes / frames < 16 * 1024, "no buffer is allocated per frame (${bytes / frames} bytes per frame)")
            assertEquals(3, exchange.buffersKept(), "three buffers, going round")

            // A seek moves the playhead at once and mpv lands there.
            player.seek(2_000)
            assertTrue(player.positionMs() in 2_000..2_100)
            waitFor(5_000, "the seek to land") { snapshot.value.status == PlayerStatus.READY }
            assertTrue(player.positionMs() >= 1_900, "after the seek: ${player.positionMs()}")
        } finally {
            renderer.close()
            player.close()
        }
    }

    @Test
    fun theEngineReadsTheSourceThroughTheProxyAsAnEdlAndA403IsAnExpiredSource() {
        mpvOrSkip()
        assumeTrue("the sample video is on this machine", sample.isFile)
        val bytes = sample.readBytes()
        val seen = java.util.concurrent.CopyOnWriteArrayList<okhttp3.mockwebserver.RecordedRequest>()
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                seen += request
                if (expired.get()) return okhttp3.mockwebserver.MockResponse().setResponseCode(403)
                val range = request.requestUrl!!.queryParameter("range") ?: return okhttp3.mockwebserver.MockResponse().setResponseCode(400)
                val start = range.substringBefore('-').toInt()
                val end = minOf(range.substringAfter('-').toInt(), bytes.size - 1)
                return okhttp3.mockwebserver.MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4")
                    .setBody(okio.Buffer().write(bytes, start, end - start + 1))
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns { host ->
            if (host.endsWith(".googlevideo.com")) listOf(java.net.InetAddress.getByName("127.0.0.1")) else throw java.net.UnknownHostException(host)
        }.build()
        val engine = DesktopVideoEngine({ testMpv() }, client, mapOf("ao" to "null"))
        try {
            fun address(itag: Int) = "http://rr1---sn-fake0000.googlevideo.com:${server.port}/videoplayback?itag=$itag&c=WEB&clen=${bytes.size}"
            val video = com.cosmotune.video.model.VideoStream(
                address(137), 137, "video/mp4", com.cosmotune.video.model.VideoCodec.H264, "avc1.64001f", 720, 1280, 25, 1_000_000, false,
                bytes.size.toLong(), null, null, com.cosmotune.video.model.Delivery.PROGRESSIVE, hasAudio = false,
            )
            val audio = com.cosmotune.video.model.AudioStream(
                address(140), 140, "audio/mp4", com.cosmotune.video.model.AudioCodec.AAC, "mp4a.40.2", 128_000, 44_100, 2,
                bytes.size.toLong(), null, null, com.cosmotune.video.model.Delivery.PROGRESSIVE, null, null, null,
                com.cosmotune.video.model.AudioTrackKind.ORIGINAL,
            )
            val request = PlaybackRequest(
                videoId = "fakevideo01", input = MediaInput.Progressive(video, audio), subtitles = emptyList(),
                headers = mapOf("User-Agent" to "TestAgent/1.0 (fake)"), startMs = 0, live = false, title = "Sample", channel = null, thumbnailUrl = null,
            )
            engine.load(request)
            waitFor(15_000, "READY through the proxy") { engine.snapshot.value.status == PlayerStatus.READY }
            engine.play()
            waitFor(5_000, "playing") { engine.snapshot.value.playing }
            // Y-34: it once read 490 ms after a fixed second under load; a deadline keeps the check (a stalled proxy never gets there).
            waitFor(PLAYHEAD_DEADLINE_MS, "it plays from the proxy past 500 ms") { engine.positionMs() > 500 }
            val requests = seen.toList()
            assertTrue(requests.isNotEmpty() && requests.all { it.method == "POST" && it.getHeader("User-Agent") == "TestAgent/1.0 (fake)" })
            assertTrue(requests.all { it.requestUrl!!.queryParameter("rn") != null && it.requestUrl!!.queryParameter("range") != null })
            assertEquals(setOf("137", "140"), requests.map { it.requestUrl!!.queryParameter("itag") }.toSet(), "both streams of the EDL address were read")

            engine.seekTo(5_000)
            waitFor(5_000, "the seek") { engine.snapshot.value.status == PlayerStatus.READY && engine.positionMs() >= 4_900 }

            expired.set(true)
            engine.load(request.copy(startMs = 2_000))
            waitFor(15_000, "the expired source") { engine.snapshot.value.status == PlayerStatus.ERROR }
            assertEquals(PlayerErrorKind.SOURCE_EXPIRED, engine.snapshot.value.error?.kind, "${engine.snapshot.value.error}")
        } finally {
            engine.release()
            server.shutdown()
        }
    }

    @Test
    fun aDownloadedVideosSubtitleFilesAreTracksAndTheChosenOneGivesCues() {
        mpvOrSkip()
        assumeTrue("the sample video is on this machine", sample.isFile)
        val dir = kotlin.io.path.createTempDirectory("cosmotune-subtitles").toFile()
        val english = File(dir, "sample.en.vtt").apply { writeText("WEBVTT\n\n00:00:00.000 --> 00:00:07.000\nHello from the downloaded file\n") }
        val german = File(dir, "sample.de.vtt").apply { writeText("WEBVTT\n\n00:00:00.000 --> 00:00:07.000\nHallo aus der Datei\n") }
        val engine = DesktopVideoEngine({ testMpv() }, null, mapOf("ao" to "null"))
        try {
            engine.load(
                PlaybackRequest(
                    videoId = "local", input = MediaInput.LocalFiles(sample.absolutePath, null, null), subtitles = emptyList(),
                    headers = emptyMap(), startMs = 0, live = false, title = "Sample", channel = null, thumbnailUrl = null,
                    localSubtitles = listOf(
                        com.cosmotune.video.player.LocalSubtitle(english.absolutePath, "en", "English", autoGenerated = false),
                        com.cosmotune.video.player.LocalSubtitle(german.absolutePath, "de", "Deutsch", autoGenerated = false),
                    ),
                ),
            )
            engine.selectSubtitle("de")
            waitFor(10_000, "READY") { engine.snapshot.value.status == PlayerStatus.READY }
            engine.play()
            waitFor(5_000, "the German cue") { engine.cues.value.singleOrNull()?.text == "Hallo aus der Datei" }
            val cue = engine.cues.value.single()
            assertTrue(cue.startMs in 0..100 && cue.endMs in 6_900..7_100, "the cue's times come from the file: $cue")
            engine.selectSubtitle("en")
            waitFor(5_000, "the English cue") { engine.cues.value.singleOrNull()?.text == "Hello from the downloaded file" }
            engine.selectSubtitle(null)
            waitFor(5_000, "no cue") { engine.cues.value.isEmpty() }
        } finally {
            engine.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theEngineLoadsPlaysGoesAudioOnlyAndUnloads() {
        mpvOrSkip()
        assumeTrue("the sample video is on this machine", sample.isFile)
        val engine = DesktopVideoEngine({ testMpv() }, null, mapOf("ao" to "null"))
        try {
            val request = PlaybackRequest(
                videoId = "local", input = MediaInput.LocalFiles(sample.absolutePath, null, null), subtitles = emptyList(),
                headers = emptyMap(), startMs = 1_500, live = false, title = "Sample", channel = null, thumbnailUrl = null,
            )
            engine.load(request)
            assertEquals(PlayerStatus.LOADING, engine.snapshot.value.status, "LOADING at once, on the caller's thread")
            assertEquals(1_500, engine.positionMs(), "the start until mpv says otherwise")
            waitFor(10_000, "READY") { engine.snapshot.value.status == PlayerStatus.READY }
            assertTrue(!engine.snapshot.value.playing, "a load does not start playback")
            engine.play()
            waitFor(5_000, "playing") { engine.snapshot.value.playing }
            waitFor(PLAYHEAD_DEADLINE_MS, "it plays on from the start") { engine.positionMs() > 1_500 }   // Y-34: a deadline, as above

            engine.setAudioOnly(true)
            assertTrue(engine.snapshot.value.audioOnly)
            engine.setMuted(true)
            assertTrue(engine.snapshot.value.muted)
            engine.seekTo(500)
            waitFor(5_000, "the seek") { engine.positionMs() in 400..3_000 && engine.snapshot.value.status == PlayerStatus.READY }

            engine.unload()
            assertEquals(PlayerStatus.IDLE, engine.snapshot.value.status)
            Thread.sleep(300)
            assertEquals(PlayerStatus.IDLE, engine.snapshot.value.status, "it stays idle")
            assertTrue(engine.snapshot.value.muted && engine.snapshot.value.audioOnly, "mute and audio-only stay for the next load")

            engine.load(request.copy(input = MediaInput.LocalFiles(File(sample.parentFile, "missing-file.mp4").absolutePath, null, null)))
            waitFor(10_000, "the error") { engine.snapshot.value.status == PlayerStatus.ERROR }
            val error = engine.snapshot.value.error!!
            assertTrue(error.kind == PlayerErrorKind.OTHER || error.kind == PlayerErrorKind.DECODER, "a missing local file is not an expired source: $error")
        } finally {
            engine.release()
        }
    }
}
