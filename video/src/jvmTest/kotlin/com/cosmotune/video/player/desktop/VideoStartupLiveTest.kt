package com.cosmotune.video.player.desktop

import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.library.VideoLibraryStore
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.StartupTrace
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SegmentPolicy
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.select.StreamSelector
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.newpipe.NewPipeVideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The startup harness (opt-in: VIDEO_LIVE=true): click to first frame through the real controller, the anonymous source (no account,
 * no cookie), the real stream selector and the desktop engine with libmpv, for a few public videos. Audio goes to a null output; the
 * frames go to a 1280 x 720 surface, as the watch page asks. It prints one table row per click with the time of each stage after the
 * click, and the lines of [StartupTrace] as the app's log would show them. Nothing of the owner's profile is read: the library and
 * the settings are in a scratch folder.
 */
class VideoStartupLiveTest {

    private val videos = (System.getenv("VIDEO_STARTUP_IDS") ?: "dQw4w9WgXcQ,aqz-KE-bpKQ,M7lc1UVf-VE,jNQXAC9IVRw").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun clickToFirstFrame() = runBlocking {
        assumeTrue(System.getenv("VIDEO_LIVE") == "true")
        val stages = ConcurrentLinkedQueue<StartupTrace.Stage>()
        StartupTrace.listener = { stages += it }
        StartupTrace.sink = { _, line -> println("TRACE $line") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("cosmotune-startup").toFile()
        // VIDEO_STARTUP_MPV="name=value;name=value" tries other mpv options (a log-file, a cache setting) against the same clicks.
        val extra = System.getenv("VIDEO_STARTUP_MPV").orEmpty().split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // VIDEO_STARTUP_WARM=false opens the streams as before: each first piece asked for only when mpv opens it. VIDEO_STARTUP_SOFTWARE=false
        // starts every load with the hardware decoder, as before.
        val engine = DesktopVideoEngine({ testMpv() }, null, mapOf("ao" to "null") + extra, warmStart = System.getenv("VIDEO_STARTUP_WARM") != "false",
            softwareStart = System.getenv("VIDEO_STARTUP_SOFTWARE") != "false")
        engine.frames.request(this, 1280, 720, VideoScale.FIT)
        val noSegments = object : SegmentProvider {
            override suspend fun segments(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment> = emptyList()
        }
        val files = FileLibraryFiles(dir)
        val controller = VideoPlayerController(
            NewPipeVideoSource(), engine, VideoLibraryStore(files, scope, System::currentTimeMillis),
            // VIDEO_STARTUP_QUALITY caps Auto (720 for "start at 720p"); unset is the app's default, Auto with no cap.
            FakeVideoSettingsRepository(VideoSettings(wifiQuality = System.getenv("VIDEO_STARTUP_QUALITY")?.toIntOrNull())),
            noSegments, StreamSelector(), SegmentPolicy, { false }, scope, System::currentTimeMillis,
        )
        val rows = ArrayList<String>()
        try {
            val rounds = (System.getenv("VIDEO_STARTUP_ROUNDS") ?: "1").toInt()
            for (round in 1..rounds) for (id in videos) {
                if (System.getenv("VIDEO_STARTUP_AHEAD") == "true" && round == 1) {
                    // The pointer rests on the card first: its streams are resolved before the click, as the cards now ask.
                    controller.prefetch(id)
                    withTimeoutOrNull(20_000) { while (stages.none { it.videoId == id && it.name == "anonymous extract" }) delay(10) }
                    delay(200)
                }
                stages.clear()
                controller.play(id, startMs = 0)
                val done = withTimeoutOrNull(60_000) {
                    while (stages.none { it.videoId == id && it.name == "playback started" } || stages.none { it.videoId == id && it.name == "first frame" }) {
                        if (controller.state.value.error != null || engine.snapshot.value.status == PlayerStatus.ERROR) return@withTimeoutOrNull false
                        delay(10)
                    }
                    true
                }
                val mine = stages.filter { it.videoId == id }
                fun at(name: String) = mine.firstOrNull { it.name == name }?.sinceClickMs?.toString() ?: "-"
                val chosen = mine.firstOrNull { it.name == "streams chosen" }?.detail.orEmpty()
                rows += "round $round $id: details ${at("details ready")} | chosen ${at("streams chosen")} | loadfile ${at("mpv loadfile")} | " +
                    "file loaded ${at("mpv file loaded")} | first frame ${at("first frame")} | playing ${at("playback started")} | $chosen" +
                    (if (done == true) "" else " | DID NOT START (${controller.state.value.error ?: engine.snapshot.value.error})")
                val watchMs = System.getenv("VIDEO_STARTUP_WATCH_MS")?.toLongOrNull()
                if (watchMs != null && done == true) {
                    // Watches the hand-over to the hardware decoder: the decoder before and after, the frames dropped, the longest
                    // stretch with no new picture.
                    val decoderAtStart = engine.snapshot.value.decoderName
                    val dropsAtStart = engine.snapshot.value.droppedFrames
                    var last = engine.frames.frames.value
                    var stillSince = System.nanoTime()
                    var longestStillMs = 0L
                    val until = System.nanoTime() + watchMs * 1_000_000
                    while (System.nanoTime() < until) {
                        delay(20)
                        val now = engine.frames.frames.value
                        if (now != last) { last = now; stillSince = System.nanoTime() }
                        longestStillMs = maxOf(longestStillMs, (System.nanoTime() - stillSince) / 1_000_000)
                    }
                    val decoderAfterWatching = engine.snapshot.value.decoderName
                    // A pause hands a software start over to the hardware decoder.
                    controller.togglePlayPause()
                    delay(1_000)
                    val decoderAfterPause = engine.snapshot.value.decoderName
                    controller.togglePlayPause()
                    rows += "watch $id: decoder $decoderAtStart, after ${watchMs}ms of playing $decoderAfterWatching, after a pause $decoderAfterPause; " +
                        "${engine.snapshot.value.droppedFrames - dropsAtStart} frames dropped, no new picture for at most ${longestStillMs}ms while playing"
                }
                delay(1_500) // a second and a half of playing, as someone who clicked would watch
                controller.stop()
                delay(300)
            }
        } finally {
            StartupTrace.listener = null
            StartupTrace.sink = null
            controller.stop()
            engine.release()
            engine.awaitRelease(15_000)
            scope.cancel()
            dir.deleteRecursively()
        }
        println("STARTUP (ms after the click)")
        rows.forEach { println("STARTUP $it") }
        assertTrue(rows.isNotEmpty())
    }
}
