package com.cosmotune.video.player.desktop

import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.ui.VideoSourceContract
import okhttp3.CookieJar
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The desktop engine's rules that need no libmpv (plan task Y-12, step 7): read from the source where only the source can tell
 * (nothing allocated in the render loop, the options of spec 6.3, the reconnect variant that breaks live HLS never set), and
 * the pure parts run: the swap of the two frame buffers, the render size and the placement, the capabilities, the subtitle
 * choice, and the engine on a computer without libmpv.
 */
class DesktopEngineStructureTest {

    private val dir = "video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop"
    private val files = listOf(
        "MpvNatives.kt", "MpvLibrary.kt", "MpvPlayer.kt", "MpvRenderer.kt", "LoopbackProxy.kt", "DesktopVideoEngine.kt", "DesktopCapabilities.kt",
    )

    private fun source(name: String) = VideoSourceContract.read("$dir/$name")

    // ---- read from the source -----------------------------------------------------------------------------------------

    @Test
    fun theSevenFilesOfThePlanAreThereAndNothingElse() {
        val present = File(VideoSourceContract.root, dir).list()!!.sorted()
        assertEquals(files.sorted(), present)
    }

    @Test
    fun theRenderLoopAllocatesNoBufferAndNoBitmap() {
        val body = VideoSourceContract.functionBody(source("MpvRenderer.kt"), "renderLoop")
        for (forbidden in listOf("ByteBuffer.allocateDirect", "allocateDirect", "Bitmap(", "FrameBuffer(", "Memory(", "Pixmap.make")) {
            assertFalse(forbidden in body, "the render loop's body has no $forbidden")
        }
        assertTrue("mpv_render_context_render" in body && "beginWrite" in body && "endWrite" in body)
        // The buffer is made where the size changes, nowhere per frame.
        val beginWrite = VideoSourceContract.functionBody(source("MpvRenderer.kt"), "beginWrite")
        assertTrue("FrameBuffer(width, height)" in beginWrite && "existing.width == width && existing.height == height" in beginWrite)
    }

    @Test
    fun theUpdateCallbackOnlyWakesTheRenderThread() {
        val src = VideoSourceContract.code(source("MpvRenderer.kt"))
        val callback = src.substringAfter("private val update = object : MpvLibrary.RenderUpdate {").substringBefore("\n    }")
        assertTrue("override fun invoke(cbCtx: Pointer?) = wake()" in callback, callback)
        assertTrue("CallbackThreadInitializer(true, false" in src, "the callback thread stays attached between frames")
    }

    @Test
    fun theStreamedReconnectIsNeverSet() {
        for (name in files) assertFalse("reconnect_streamed" in source(name), "$name never names it")
        assertEquals("reconnect=1,reconnect_delay_max=30", MpvPlayer.LIVE_LAVF_OPTIONS)
        assertTrue(MpvPlayer.DEFAULT_OPTIONS.values.none { "reconnect" in it }, "reconnecting is for live loads only")
    }

    @Test
    fun theOptionsAreThoseOfSpec63() {
        val o = MpvPlayer.DEFAULT_OPTIONS
        assertEquals("libmpv", o["vo"])
        assertEquals("auto-copy", o["hwdec"])
        assertEquals("no", o["ytdl"])
        assertEquals("yes", o["keep-open"])
        assertEquals("yes", o["cache"])
        assertEquals("150MiB", o["demuxer-max-bytes"])
        assertEquals("30", o["demuxer-readahead-secs"])
        assertEquals("no", o["sub-visibility"], "our chrome draws the subtitles")
        assertEquals("yes", o["audio-pitch-correction"])
        val mac = System.getProperty("os.name").contains("mac", ignoreCase = true)
        assertEquals(if (mac) "coreaudio," else null, o["ao"])
    }

    @Test
    fun audioOutputSelectionKeepsNativeFallbackOnEveryDesktopPlatform() {
        for (os in listOf("Mac OS X", "macOS")) {
            assertEquals("coreaudio,", MpvPlayer.defaultOptions(os)["ao"], "CoreAudio first, then other compiled drivers")
        }
        for (os in listOf("Windows 11", "Linux")) {
            assertNull(MpvPlayer.defaultOptions(os)["ao"], "$os probes its available native outputs")
        }
    }

    @Test
    fun theSoftwareRenderIsRgb0IntoRgb888xAlignedTo64() {
        assertEquals("rgb0", FrameGeometry.MPV_FORMAT)
        assertEquals("sw", MpvLibrary.MPV_RENDER_API_TYPE_SW)
        val src = source("MpvRenderer.kt")
        assertTrue("ColorType.RGB_888X, ColorAlphaType.OPAQUE" in src)
        assertTrue(".alignedSlice(FrameGeometry.ALIGNMENT)" in src)
        assertTrue("Image.makeFromPixmap(next.pixmap)" in src, "an image per published frame wraps the buffer, no copy")
        assertEquals(1280, FrameGeometry.strideOf(320))
        assertEquals(1344, FrameGeometry.strideOf(321))
        assertEquals(7680, FrameGeometry.strideOf(1920))
        assertEquals(5120, FrameGeometry.strideOf(1280))
    }

    @Test
    fun theProxysClientHasNoCookieJarAndNothingImportsTheExtractor() {
        assertSame(CookieJar.NO_COOKIES, LoopbackProxy.defaultClient().cookieJar)
        for (name in files) {
            val text = source(name)
            assertFalse("org.schabi" in text, "$name does not import the extractor")
            assertFalse(".cookieJar(" in text, "$name sets no cookie jar")
            assertFalse(Regex("\\bYouTube\\b|\\bYT\\b").containsMatchIn(text), "$name names no service")
        }
    }

    @Test
    fun everyCommandOfTheEngineGoesToItsOwnThread() {
        val src = source("DesktopVideoEngine.kt")
        for (name in listOf("seekTo", "setSpeed", "setVolume", "setMuted", "setAudioOnly", "selectSubtitle", "unload", "load")) {
            assertTrue("post {" in VideoSourceContract.functionBody(src, name), "$name hands its work to the engine thread")
        }
        assertTrue("override fun play() = post {" in src && "override fun pause() = post {" in src)
        val position = VideoSourceContract.functionBody(src, "positionMs")
        assertFalse("mpv_" in position || "post" in position, "positionMs reads a clock")
    }

    // ---- the frame exchange -------------------------------------------------------------------------------------------

    @Test
    fun theRendererNeverWritesTheBufferTheScreenHoldsNorTheReadyFrame() {
        val exchange = FrameExchange()
        val first = exchange.beginWrite(64, 36)
        exchange.endWrite(first, published = true)
        assertSame(first, exchange.acquire(), "the screen takes the published frame")
        val second = exchange.beginWrite(64, 36)
        assertNotSame(first, second, "never the held buffer")
        exchange.endWrite(second, published = true)
        val third = exchange.beginWrite(64, 36)
        assertTrue(third !== first && third !== second, "neither the held one nor the frame ready to be taken")
        assertSame(second, exchange.acquire(), "while the third is written, the screen still finds the ready frame")
        exchange.endWrite(third, published = true)
        assertSame(third, exchange.acquire())
        assertSame(first, exchange.beginWrite(64, 36), "the buffers go round: the oldest is written again")
        assertEquals(3, exchange.buffersKept(), "three buffers per size, no more")
    }

    @Test
    fun aFrameNobodyTookIsReplacedByTheNewerOne() {
        val exchange = FrameExchange()
        val held = exchange.beginWrite(64, 36).also { exchange.endWrite(it, true) }
        exchange.acquire()
        val a = exchange.beginWrite(64, 36).also { exchange.endWrite(it, true) }
        val b = exchange.beginWrite(64, 36).also { exchange.endWrite(it, true) }
        assertNotSame(a, b)
        assertSame(b, exchange.acquire(), "the screen takes the newest")
        val next = exchange.beginWrite(64, 36)
        assertSame(held, next, "the buffer the screen let go is free")
        exchange.endWrite(next, true)
        assertSame(a, exchange.beginWrite(64, 36), "and so is the frame it skipped")
    }

    @Test
    fun beforeTheScreenHoldsAnythingAFinishedFrameStaysReady() {
        val exchange = FrameExchange()
        val a = exchange.beginWrite(64, 36)
        exchange.endWrite(a, published = true)
        val b = exchange.beginWrite(64, 36)
        assertNotSame(a, b, "the next frame goes to another buffer")
        assertSame(a, exchange.acquire(), "the finished frame is still there while the next one is written")
    }

    @Test
    fun aNewSizeMakesNewBuffersOnlyOnce() {
        val exchange = FrameExchange()
        val small = exchange.beginWrite(64, 36).also { exchange.endWrite(it, true) }
        exchange.acquire()
        val big = exchange.beginWrite(128, 72).also { exchange.endWrite(it, true) }
        assertEquals(128, big.width)
        exchange.acquire()
        val again = exchange.beginWrite(128, 72)
        assertNotSame(small, again)
        assertNotSame(big, again, "a third buffer of the new size")
        exchange.endWrite(again, true)
        exchange.acquire()
        val reused = exchange.beginWrite(128, 72)
        assertTrue(reused !== small && reused.width == 128, "the old size's buffer was replaced, the new ones are reused")
        exchange.endWrite(reused, true)
        exchange.acquire()
        assertTrue(exchange.beginWrite(128, 72) === big || exchange.beginWrite(128, 72) === again, "reused, not made again")
    }

    @Test
    fun clearShowsNothingAndDropsAFrameOfTheOldVideo() {
        val exchange = FrameExchange()
        val a = exchange.beginWrite(64, 36).also { exchange.endWrite(it, true) }
        assertSame(a, exchange.acquire())
        val inFlight = exchange.beginWrite(64, 36)
        val before = exchange.frames.value
        exchange.clear()
        assertTrue(exchange.frames.value > before, "the screen is told to redraw")
        exchange.endWrite(inFlight, published = true)
        assertNull(exchange.acquire(), "nothing is shown, not even the frame that was being written")
        exchange.clear(freeBuffers = true)
        assertNull(exchange.acquire())
        assertEquals(0, exchange.buffersKept(), "an unload lets the buffers go")
    }

    @Test
    fun theTargetIsTheLargestSurfaceCappedAtTheVideo() {
        val exchange = FrameExchange()
        var changes = 0
        exchange.onChange = { changes++ }
        assertEquals(0L, exchange.target(), "no video yet")
        exchange.setVideoSize(1920, 1080)
        val page = Any()
        val full = Any()
        exchange.request(page, 640, 360, VideoScale.FIT)
        assertEquals(FrameGeometry.pack(640, 360), exchange.target())
        exchange.request(full, 3840, 2160, VideoScale.FIT)
        assertEquals(FrameGeometry.pack(1920, 1080), exchange.target(), "never larger than the video")
        val before = changes
        exchange.request(full, 3840, 2160, VideoScale.FIT)
        assertEquals(before, changes, "the same size again is no change")
        exchange.forget(full)
        assertEquals(FrameGeometry.pack(640, 360), exchange.target())
    }

    // ---- geometry -----------------------------------------------------------------------------------------------------

    @Test
    fun theRenderSizeFitsOrCoversTheBoxAndNeverExceedsTheVideo() {
        fun size(bw: Int, bh: Int, vw: Int, vh: Int, s: VideoScale) = FrameGeometry.renderSize(bw, bh, vw, vh, s).let { FrameGeometry.widthOf(it) to FrameGeometry.heightOf(it) }
        assertEquals(1280 to 720, size(1280, 720, 1920, 1080, VideoScale.FIT))
        assertEquals(1280 to 720, size(3840, 2160, 1280, 720, VideoScale.FIT), "capped at the video's size")
        assertEquals(1000 to 563, size(1000, 1000, 1920, 1080, VideoScale.FIT))
        assertEquals(1778 to 1000, size(1000, 1000, 1920, 1080, VideoScale.FILL))
        assertEquals(203 to 360, size(640, 360, 1080, 1920, VideoScale.FIT), "a vertical video in a wide box")
        assertEquals(0L, FrameGeometry.renderSize(0, 360, 1920, 1080, VideoScale.FIT))
        assertEquals(0L, FrameGeometry.renderSize(640, 360, 0, 0, VideoScale.FIT))
    }

    @Test
    fun fitLetterboxesAndFillCrops() {
        val out = FloatArray(8)
        FrameGeometry.placement(1000f, 1000f, 1600, 900, VideoScale.FIT, out)
        assertEquals(listOf(0f, 0f, 1600f, 900f, 0f, 218.75f, 1000f, 781.25f), out.toList(), "bars above and below")
        FrameGeometry.placement(1000f, 1000f, 1600, 900, VideoScale.FILL, out)
        assertEquals(listOf(350f, 0f, 1250f, 900f, 0f, 0f, 1000f, 1000f), out.toList(), "the centre square of the picture")
        FrameGeometry.placement(1600f, 900f, 1280, 720, VideoScale.FIT, out)
        listOf(0f, 0f, 1280f, 720f, 0f, 0f, 1600f, 900f).forEachIndexed { i, expected ->
            assertEquals(expected, out[i], 0.01f, "the same shape fills the box (value $i)")
        }
    }

    // ---- capabilities, subtitles, the engine without libmpv ------------------------------------------------------------

    @Test
    fun theCapabilitiesAreThoseOfSpec63() {
        val caps = DesktopCapabilities.of(2160)
        assertEquals(listOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1), caps.video.map { it.codec })
        assertTrue(caps.video.all { it.maxWidth == 3840 && it.maxHeight == 2160 && it.maxFps == 60 && !it.hardware })
        assertTrue(caps.opus)
        assertFalse(caps.hdrDisplay)
        assertFalse(caps.sideloadedDash)
        assertEquals(1440, caps.autoCeilingHeight)
        assertEquals(2160, caps.displayHeightPx)
        assertTrue(DesktopCapabilities.largestDisplayHeight() > 0)
    }

    @Test
    fun theSubtitleOfALanguagePrefersAPersonsTrackAndTheExactLanguage() {
        val choices = listOf(
            DesktopVideoEngine.SubtitleChoice("https://media.example.com/en-auto.vtt", "en", "English (auto-generated)", autoGenerated = true),
            DesktopVideoEngine.SubtitleChoice("https://media.example.com/en-gb.vtt", "en-GB", "English (United Kingdom)", autoGenerated = false),
            DesktopVideoEngine.SubtitleChoice("https://media.example.com/en.vtt", "en", "English", autoGenerated = false),
            DesktopVideoEngine.SubtitleChoice("https://media.example.com/de.vtt", "de", "Deutsch", autoGenerated = false),
        )
        assertEquals("https://media.example.com/en.vtt", DesktopVideoEngine.pickSubtitle(choices, "en")?.url)
        assertEquals("https://media.example.com/en-gb.vtt", DesktopVideoEngine.pickSubtitle(choices, "EN-gb")?.url)
        assertEquals("https://media.example.com/en.vtt", DesktopVideoEngine.pickSubtitle(choices, "en-US")?.url, "the base language when the exact one is missing")
        assertNull(DesktopVideoEngine.pickSubtitle(choices, "fr"))
        assertNull(DesktopVideoEngine.pickSubtitle(choices, null))
        assertEquals(
            listOf("Origin: https://media.example.com"),
            DesktopVideoEngine.headerFields(mapOf("User-Agent" to "Agent/1", "Origin" to "https://media.example.com", "X-List" to "a,b")),
        )
    }

    @Test
    fun aRequestOffersItsTracksAndADownloadsFiles() {
        val request = PlaybackRequest(
            videoId = "fakevideo01", input = MediaInput.LocalFiles("C:/Videos/clip.mp4", null, null),
            subtitles = listOf(com.cosmotune.video.model.SubtitleTrack("https://media.example.com/en.vtt", "text/vtt", "en", "English", false)),
            headers = emptyMap(), startMs = 0, live = false, title = "Sample", channel = null, thumbnailUrl = null,
            localSubtitles = listOf(com.cosmotune.video.player.LocalSubtitle("C:/Videos/clip.de.vtt", "de", "Deutsch", autoGenerated = true)),
        )
        val choices = DesktopVideoEngine.subtitleChoices(request)
        assertEquals(listOf("https://media.example.com/en.vtt", "C:/Videos/clip.de.vtt"), choices.map { it.url })
        assertEquals("C:/Videos/clip.de.vtt", DesktopVideoEngine.pickSubtitle(choices, "de")?.url)
        assertTrue(choices.last().autoGenerated && choices.last().label == "Deutsch")
    }

    @Test
    fun withoutLibmpvTheEngineSaysUnsupportedAndEveryCommandDoesNothing() {
        val engine = DesktopVideoEngine({ null }, null, emptyMap())
        try {
            val request = PlaybackRequest(
                videoId = "fakevideo01", input = MediaInput.Hls("https://media.cosmotune.invalid/live.m3u8"), subtitles = emptyList(),
                headers = emptyMap(), startMs = 12_000, live = false, title = "Sample", channel = null, thumbnailUrl = null,
            )
            engine.load(request)
            val until = System.currentTimeMillis() + 5_000
            while (engine.snapshot.value.status != PlayerStatus.ERROR && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals(PlayerStatus.ERROR, engine.snapshot.value.status)
            assertEquals(PlayerErrorKind.UNSUPPORTED, engine.snapshot.value.error?.kind)
            engine.play()
            engine.seekTo(30_000)
            engine.setSpeed(2f)
            engine.selectSubtitle("en")
            engine.setAudioOnly(true)
            engine.load(request)
            assertEquals(PlayerErrorKind.UNSUPPORTED, engine.snapshot.value.error?.kind, "known at once the second time")
            assertTrue(engine.snapshot.value.audioOnly)
            assertEquals(12_000, engine.positionMs())
        } finally {
            engine.release()
        }
        assertEquals(PlayerStatus.IDLE, engine.snapshot.value.status)
    }
}
