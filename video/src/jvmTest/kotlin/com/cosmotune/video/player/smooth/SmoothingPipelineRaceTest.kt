package com.cosmotune.video.player.smooth

import com.cosmotune.video.player.desktop.FrameBuffer
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessSettings
import com.sun.jna.Pointer
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen draws the pipeline's snapshots while the render thread closes them (the ring reuses a snapshot, a new size rebuilds the
 * whole pool, smoothing stops, the engine closes the pipeline). Drawing with an image that was closed meanwhile is not an exception
 * but a native crash of the whole process, so each of these runs a drawing thread against the thread that closes, many times over:
 * the test passing means the process lived and nothing was thrown.
 */
class SmoothingPipelineRaceTest {
    private val interval = 41_708_333L // 23.976 fps

    private fun frames(width: Int, height: Int, count: Int): List<FrameBuffer> = List(count) { k ->
        FrameBuffer(width, height).also { Pointer(it.address).setMemory(0L, (it.stride * it.height).toLong(), (40 + 20 * k).toByte()) }
    }

    /** Draws as fast as it can until [stop], into a surface of its own; counts the draws that showed something. */
    private class Drawer(val pipeline: SmoothingPipeline, val stop: AtomicBoolean, val failure: AtomicReference<Throwable?>) : Thread("test-draw") {
        val drawn = AtomicInteger()

        override fun run() {
            org.jetbrains.skia.impl.Library.staticLoad()
            val surface = Surface.makeRasterN32Premul(320, 180)
            try {
                val whole = Rect.makeWH(320f, 180f)
                var now = 10_000_000_000L
                while (!stop.get()) {
                    now += 7_000_000L
                    // The size the plain frame would be placed for does not matter to the race; the pipeline places by [src] and [dst].
                    if (pipeline.draw(surface.canvas, 320f, 180f, whole, whole, 320, 180, now)) drawn.incrementAndGet()
                }
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                surface.close()
            }
        }
    }

    @Test
    fun drawingWhileTheRingReusesSnapshotsAndTheSizeChangesRebuildsThePoolIsSafe() {
        val pipeline = SmoothingPipeline(displayHz = { 60f })
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        val stop = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val drawer = Drawer(pipeline, stop, failure).also { it.start() }
        try {
            val big = frames(320, 180, 3)
            val small = frames(256, 144, 3)
            var at = 10_000_000_000L
            for (k in 0 until 600) {
                // a new size every 30 frames: the whole pool is closed and made again under the drawing thread
                val set = if ((k / 30) % 2 == 0) big else small
                pipeline.onSourceFrame(set[k % set.size], at)
                at += interval
                if (k % 100 == 99) {
                    pipeline.configure(SmoothnessSettings()) // smoothing stops: the pool is let go
                    pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
                }
            }
        } finally {
            stop.set(true)
            drawer.join(10_000)
            pipeline.close()
        }
        assertNull(failure.get(), "the drawing thread threw: ${failure.get()}")
        assertTrue(drawer.drawn.get() > 0, "something was drawn while this went on")
        assertTrue(drawer.isAlive.not())
    }

    @Test
    fun closingThePipelineWhileTheScreenDrawsIsSafe() {
        repeat(25) {
            val pipeline = SmoothingPipeline(displayHz = { 60f })
            pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
            val set = frames(320, 180, 3)
            var at = 10_000_000_000L
            for (k in 0 until 14) {
                pipeline.onSourceFrame(set[k % set.size], at)
                at += interval
            }
            assertTrue(pipeline.engaged(), "smoothing is in effect before the race")
            val stop = AtomicBoolean()
            val failure = AtomicReference<Throwable?>()
            val drawer = Drawer(pipeline, stop, failure).also { it.start() }
            Thread.sleep(5)
            val closed = CountDownLatch(1)
            Thread { pipeline.close(); closed.countDown() }.start()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            stop.set(true)
            drawer.join(10_000)
            assertNull(failure.get(), "the drawing thread threw: ${failure.get()}")
        }
    }

    @Test
    fun aPoolRebuiltOnAnotherSizeDoesNotDrawTheOldPair() {
        val pipeline = SmoothingPipeline(displayHz = { 60f })
        try {
            pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
            val a = frames(320, 180, 3)
            var at = 10_000_000_000L
            for (k in 0 until 14) {
                pipeline.onSourceFrame(a[k % a.size], at)
                at += interval
            }
            val b = frames(256, 144, 1)
            pipeline.onSourceFrame(b[0], at) // the pool is made again for the new size
            org.jetbrains.skia.impl.Library.staticLoad()
            val surface = Surface.makeRasterN32Premul(256, 144)
            val whole = Rect.makeWH(256f, 144f)
            // the pair now is the new frame alone (its older picture was of the closed pool): drawing it does not touch a closed image
            assertEquals(true, pipeline.draw(surface.canvas, 256f, 144f, whole, whole, 256, 144, at + interval / 2))
            surface.close()
        } finally {
            pipeline.close()
        }
    }
}
