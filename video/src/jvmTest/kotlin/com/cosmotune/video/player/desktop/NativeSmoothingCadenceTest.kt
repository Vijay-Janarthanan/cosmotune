package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.*
import com.cosmotune.video.player.smooth.SmoothingPipeline
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** Optional wall-clock probe: real mpv decoding and app smoothing, with native raster readbacks.
 * This measures submissions to a raster surface, not a physical display's scanout. */
class NativeSmoothingCadenceTest {
    @Test fun decoded24FpsVideoReportsMeasuredChangingOutputAndFrameTimes() {
        val folder = File("../build/video-playback-review")
        val media = File(folder, "cadence24.mp4")
        val mpv = testMpv()
        assumeTrue("Generated local clip and libmpv required", media.isFile && mpv != null)
        org.jetbrains.skia.impl.Library.staticLoad()
        val snapshots = MutableStateFlow(PlayerSnapshot.Idle)
        val exchange = FrameExchange()
        val pipeline = SmoothingPipeline(displayHz = { 60f })
        exchange.smoothing = pipeline
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.SMOOTH, strength = 100))
        val player = MpvPlayer(mpv!!, snapshots, MutableStateFlow(emptyList()),
            MpvPlayer.DEFAULT_OPTIONS + ("ao" to "null"), latestLoad = { 1 }, onVideoSize = exchange::setVideoSize)
        val renderer = MpvRenderer(mpv, player.handle, exchange)
        val surface = Surface.makeRasterN32Premul(640, 360)
        val bitmap = Bitmap().also { it.allocN32Pixels(640, 360) }
        val whole = Rect.makeWH(640f, 360f)
        try {
            assertTrue(renderer.start())
            exchange.request(Any(), 640, 360, VideoScale.FIT)
            player.load(1, media.absolutePath, 0, false, true, false, null, emptyList())
            val readyDeadline = System.nanoTime() + 15_000_000_000L
            while (snapshots.value.status != PlayerStatus.READY) {
                check(System.nanoTime() < readyDeadline) { "Native player did not become ready" }
                Thread.sleep(10)
            }
            player.set("pause", "no")
            while (exchange.frames.value < 18) {
                check(System.nanoTime() < readyDeadline) { "Native decoder did not produce frames" }
                Thread.sleep(10)
            }
            val start = System.nanoTime()
            val frameTimes = ArrayList<Long>()
            val completions = ArrayList<Long>()
            var previous: Long? = null
            var changed = 0
            val period = 1_000_000_000L / 60
            var scheduled = start
            while (System.nanoTime() - start < 4_000_000_000L) {
                val waiting = scheduled - System.nanoTime()
                if (waiting > 0) LockSupport.parkNanos(waiting)
                val before = System.nanoTime()
                pipeline.vsync(before)
                if (pipeline.draw(surface.canvas, 640f, 360f, whole, whole, 640, 360, before)) {
                    assertTrue(surface.readPixels(bitmap, 0, 0))
                    var fingerprint = 17L
                    for (y in 20 until 360 step 40) for (x in 10 until 640 step 20) {
                        fingerprint = fingerprint * 31 + bitmap.getColor(x, y)
                    }
                    if (previous != fingerprint) changed++
                    previous = fingerprint
                    val completed = System.nanoTime()
                    frameTimes += completed - before
                    completions += completed
                }
                scheduled += period
                // Missed deadlines count as gaps; do not run a burst of fake catch-up frames.
                val now = System.nanoTime()
                if (scheduled < now) scheduled += ((now - scheduled) / period + 1) * period
            }
            pipeline.vsync(System.nanoTime() + 500_000_001L)
            assertTrue(completions.size > 24, "No meaningful native output: ${completions.size}")
            val ordered = frameTimes.sorted()
            fun percentile(fraction: Double) = ordered[((ordered.size - 1) * fraction).toInt()] / 1e6
            val gaps = completions.zipWithNext { a, b -> (b - a) / 1e6 }
            val report = "source_clip_fps=24\nsource_readout=${pipeline.readout.value.sourceFps}\n" +
                "changing_outputs=$changed\nsubmissions=${completions.size}\n" +
                "rolling_output_fps=${pipeline.readout.value.presentedFps}\n" +
                "average_submission_fps=${(completions.size - 1) * 1e9 / (completions.last() - completions.first())}\n" +
                "draw_and_readback_p50_ms=${percentile(.5)}\ndraw_and_readback_p95_ms=${percentile(.95)}\n" +
                "submission_gaps_over_25ms=${gaps.count { it > 25 }}\nmax_submission_gap_ms=${gaps.maxOrNull()}\n" +
                "decoder_dropped_frames=${snapshots.value.droppedFrames}\n" +
                "scope=Native decoder plus app smoothing and raster readback; no window/display scanout claim\n"
            File(folder, "native-smoothing-report.txt").writeText(report)
            println(report)
            assertTrue(changed > 24, "Output pictures did not advance: $report")
        } finally {
            renderer.close(); player.close(); exchange.smoothing = null
            pipeline.close(); bitmap.close(); surface.close()
        }
    }
}
