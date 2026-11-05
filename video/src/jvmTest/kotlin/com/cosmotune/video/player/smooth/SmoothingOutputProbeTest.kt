package com.cosmotune.video.player.smooth

import com.cosmotune.video.player.desktop.FrameBuffer
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessSettings
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

/** Read actual native raster pixels, not only the status label. No live video or listener session is needed. */
class SmoothingOutputProbeTest {
    init { org.jetbrains.skia.impl.Library.staticLoad() }
    private val width = 64
    private val height = 48
    private val sourcePeriod = 1_000_000_000L / 24
    private val whole = Rect.makeWH(width.toFloat(), height.toFloat())
    private fun frame(left: Int) = FrameBuffer(width, height).also { buffer ->
        val bytes = ByteArray(buffer.stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val value = if (x in left until left + 8 && y in 16 until 32) 220 else 40
            for (channel in 0..2) bytes[y * buffer.stride + x * 4 + channel] = value.toByte()
        }
        Pointer(buffer.address).write(0, bytes, 0, bytes.size)
    }
    private fun pixels(pipeline: SmoothingPipeline, surface: Surface, bitmap: Bitmap, now: Long): IntArray {
        assertTrue(pipeline.draw(surface.canvas, width.toFloat(), height.toFloat(), whole, whole, width, height, now))
        assertTrue(surface.readPixels(bitmap, 0, 0))
        return IntArray(width) { (bitmap.getColor(it, 20) shr 16) and 255 }
    }

    @Test fun delayedMotionAnalysisStillProducesChangingIntermediatePictures() {
        val held = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor().apply { submit { held.await() } }
        var clock = 10_000_000_000L
        val pipeline = SmoothingPipeline(displayHz = { 60f }, nanos = { clock }, analysisExecutor = executor)
        val surface = Surface.makeRasterN32Premul(width, height)
        val bitmap = Bitmap().also { it.allocN32Pixels(width, height) }
        try {
            pipeline.configure(SmoothnessSettings(mode = SmoothMode.SMOOTH, strength = 100))
            repeat(14) { i -> clock = 10_000_000_000L + i * sourcePeriod; pipeline.onSourceFrame(frame(10 + i * 2), clock) }
            val at = clock
            clock = at + sourcePeriod / 4
            val early = pixels(pipeline, surface, bitmap, clock)
            clock = at + sourcePeriod * 3 / 4
            val late = pixels(pipeline, surface, bitmap, clock)
            assertTrue(early[35] > late[35] + 50, "older-only strip fades instead of being held: ${early[35]} -> ${late[35]}")
            assertTrue(late[43] > early[43] + 50, "newer-only strip advances while estimator is unavailable")
        } finally { pipeline.close(); held.countDown(); bitmap.close(); surface.close() }
    }

    private fun renderProbe(dropEvery: Int?): Pair<Float, Int> {
        var clock = 10_000_000_000L
        val pipeline = SmoothingPipeline(displayHz = { 60f }, nanos = { clock })
        val surface = Surface.makeRasterN32Premul(width, height)
        val bitmap = Bitmap().also { it.allocN32Pixels(width, height) }
        try {
            pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND, strength = 100))
            var source = 0
            repeat(12) { clock = 10_000_000_000L + source * sourcePeriod; pipeline.onSourceFrame(frame(4 + source % 20 * 2), clock); source++ }
            val start = clock + sourcePeriod
            var changed = 0
            var previous: IntArray? = null
            repeat(120) { display ->
                clock = start + display * 1_000_000_000L / 60
                while (10_000_000_000L + source * sourcePeriod <= clock) {
                    pipeline.onSourceFrame(frame(4 + source % 20 * 2), 10_000_000_000L + source * sourcePeriod)
                    source++
                }
                if (dropEvery == null || display % dropEvery != 0) {
                    val row = pixels(pipeline, surface, bitmap, clock)
                    if (previous?.contentEquals(row) != true) changed++
                    previous = row
                }
            }
            pipeline.vsync(clock + 500_000_001L)
            return assertNotNull(pipeline.readout.value.presentedFps) to changed
        } finally { pipeline.close(); bitmap.close(); surface.close() }
    }

    @Test fun a24FpsSourceReallyCreatesChanging60FpsRasterOutput() {
        val (fps, changed) = renderProbe(null)
        assertTrue(fps in 59f..61f, "actual output cadence $fps")
        assertTrue(changed >= 110, "$changed changing raster outputs from 120 scheduled presentations")
    }

    @Test fun missedPresentationsAreVisibleInTheRealPipelineReadout() {
        val (fps, changed) = renderProbe(5)
        assertTrue(fps in 47f..49f, "actual output cadence includes skipped submissions: $fps")
        assertTrue(changed in 88..96, "changing outputs: $changed")
    }
}
