package com.cosmotune.video.player.smooth

import com.cosmotune.video.player.desktop.FrameBuffer
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessSettings
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole pipeline with a fake clock and frames written into real frame buffers: the rate is measured, smoothing comes into
 * effect only when it should, the sound is shifted by one frame and put back, the estimator answers on its own thread, and the
 * drawn row shows the moving square between its two places.
 */
class SmoothingPipelineTest {
    private val width = 640
    private val height = 360
    private val interval = 41_708_333L // 23.976 fps
    private val delays = mutableListOf<Double>()
    private val pipeline = SmoothingPipeline(onAudioDelay = { delays += it }, displayHz = { 60f })

    @AfterTest
    fun close() = pipeline.close()

    private fun noise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }

    private fun texture(x: Float, y: Float, seed: Int): Float {
        val gx = x / 4f
        val gy = y / 4f
        val x0 = Math.floor(gx.toDouble()).toInt()
        val y0 = Math.floor(gy.toDouble()).toInt()
        val fx = gx - x0
        val fy = gy - y0
        val t = noise(x0, y0, seed) * (1 - fx) + noise(x0 + 1, y0, seed) * fx
        val b = noise(x0, y0 + 1, seed) * (1 - fx) + noise(x0 + 1, y0 + 1, seed) * fx
        return t * (1 - fy) + b * fy
    }

    /** A frame buffer holding a dark textured background and a bright textured 64 pixel square whose left edge is at [left]. */
    private fun frame(left: Int): FrameBuffer {
        val buffer = FrameBuffer(width, height)
        val bytes = ByteArray(buffer.stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val inside = x >= left && x < left + 64 && y in 140 until 204
            val level = if (inside) 190f + 40f * texture((x - left).toFloat(), (y - 140).toFloat(), 2) else 40f + 30f * texture(x.toFloat(), y.toFloat(), 1)
            val v = level.toInt().coerceIn(0, 255).toByte()
            val at = y * buffer.stride + x * 4
            bytes[at] = v; bytes[at + 1] = v; bytes[at + 2] = v
        }
        Pointer(buffer.address).write(0, bytes, 0, bytes.size)
        return buffer
    }

    /** Feeds [count] frames, the square moving 12 pixels each, at the fake times of a 23.976 fps video; returns the time of the last. */
    private fun play(count: Int, start: Long = 10_000_000_000L): Long {
        var at = start
        for (k in 0 until count) {
            pipeline.onSourceFrame(frame(100 + 12 * k), at)
            Thread.sleep(25) // the estimator answers well within a source frame, as it must
            if (k < count - 1) at += interval
        }
        return at
    }

    private fun row(now: Long, last: Long): IntArray? {
        org.jetbrains.skia.impl.Library.staticLoad()
        val surface = Surface.makeRasterN32Premul(width, height)
        val whole = Rect.makeWH(width.toFloat(), height.toFloat())
        if (!pipeline.draw(surface.canvas, width.toFloat(), height.toFloat(), whole, whole, width, height, now)) return null
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        surface.readPixels(bitmap, 0, 0)
        return IntArray(width) { x -> (bitmap.getColor(x, 170) shr 16) and 0xff }
    }

    @Test
    fun offDrawsNothingAndShiftsNothing() {
        pipeline.configure(SmoothnessSettings())
        play(20)
        assertTrue(delays.isEmpty())
        assertTrue(!pipeline.engaged())
        assertNull(row(10_900_000_000L, 0))
    }

    @Test
    fun smoothComesIntoEffectAfterTheRateIsMeasuredAndDrawsTheSquareHalfWay() {
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.SMOOTH))
        val last = play(14)
        assertTrue(pipeline.engaged())
        val source = assertNotNull(pipeline.readout.value.sourceFps)
        assertTrue(abs(source - 23.976f) < 0.2f, "measured $source")
        assertEquals(SmoothMode.SMOOTH, pipeline.readout.value.mode)
        // the sound waits one source frame so that the picture, which needs the newer frame, stays in step
        assertTrue(delays.isNotEmpty() && abs(delays.last() + interval / 1e9) < 0.005, "audio delay $delays")

        // the last pair: the square went from 100 + 12 * 12 to 100 + 12 * 13; half way is its left edge at 250
        val half = last + interval / 2
        var pixels: IntArray? = null
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            pixels = row(half, last)
            if (pixels != null && pixels[244] < 110 && pixels[256] > 170) break // the field of the pair has arrived
            Thread.sleep(20)
        }
        val p = assertNotNull(pixels)
        assertTrue(p[244] < 110, "left of the half way square: ${p[244]}")
        assertTrue(p[256] > 170, "inside its left edge: ${p[256]}")
        assertTrue(p[250 + 64 - 6] > 170, "inside its right edge: ${p[250 + 64 - 6]}")
        assertTrue(p[250 + 64 + 6] < 110, "right of it: ${p[250 + 64 + 6]}")

        // drawn at the screen's rate: ten draws a sixtieth of a second apart are a measured 60 fps
        for (i in 1..10) row(half + i * 16_666_667L, last)
        pipeline.vsync(half + 10 * 16_666_667L + 500_000_000L) // past the readout's half second
        val readout = pipeline.readout.value
        assertEquals(60f, readout.presentedFps)
        assertEquals("24 → 60 fps · Smooth", readout.label())
    }

    @Test
    fun aVideoOf30FramesAndMoreIsLeftAloneWhileOnlyBelow30IsOn() {
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        var at = 10_000_000_000L
        for (k in 0 until 20) {
            pipeline.onSourceFrame(frame(100), at)
            at += 16_666_667L // a 60 fps video
        }
        assertTrue(!pipeline.engaged())
        assertEquals(SmoothMode.OFF, pipeline.readout.value.mode)
        assertNull(pipeline.readout.value.label(), "no conversion is claimed")
        assertTrue(assertNotNull(pipeline.readout.value.notice).contains("already 60 fps"))
        assertTrue(delays.isEmpty(), "the sound is not touched")
    }

    @Test
    fun turningItOffPutsTheSoundBackAndShowsThePlainFrameAgain() {
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        play(14)
        assertTrue(pipeline.engaged())
        assertTrue(delays.last() < 0.0)
        pipeline.configure(SmoothnessSettings())
        assertEquals(0.0, delays.last())
        assertTrue(!pipeline.engaged())
        assertEquals(SmoothMode.OFF, pipeline.readout.value.mode)
    }

    @Test
    fun aNewVideoForgetsTheOldOnesRateAndShift() {
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        play(14)
        pipeline.reset()
        assertEquals(0.0, delays.last())
        assertTrue(!pipeline.engaged())
        assertNull(pipeline.readout.value.sourceFps)
    }

    @Test
    fun aFrameThatIsAlreadyOnTheWayBetweenFramesHoldsTheNewerOneAfterTheNextIsDue() {
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        val last = play(14)
        // long after the next frame was due (a pause): the newer frame is shown as it is, and nothing keeps animating
        val paused = last + 400_000_000L
        assertTrue(!pipeline.animating(paused))
        assertNotNull(row(paused, last))
        assertTrue(pipeline.animating(last + interval / 2))
    }
}
