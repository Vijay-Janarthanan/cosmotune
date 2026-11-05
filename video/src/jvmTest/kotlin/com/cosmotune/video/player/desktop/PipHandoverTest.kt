package com.cosmotune.video.player.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.smooth.SmoothingPipeline
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessSettings
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picture moves between two Compose windows (the page and the picture-in-picture window) with one engine behind them: both draw
 * through the same exchange and the same smoothing pipeline, for a moment at once. This opens, returns, closes and opens again 20
 * times in a headless Compose scene while a thread plays the render thread (frames written and published, smoothing fed, at 25
 * frames a second), and checks that nothing throws and the picture is still drawn at the end. A surface closing a picture another one
 * still draws would crash the process, not fail an assertion.
 */
class PipHandoverTest {
    private var page by mutableStateOf(true)
    private var pip by mutableStateOf(false)

    private fun settle(scene: ImageComposeScene, clock: LongArray, steps: Int = 3) {
        repeat(steps) {
            Snapshot.sendApplyNotifications()
            clock[0] += 16_000_000L
            scene.render(clock[0])
            Thread.sleep(14)
        }
    }

    @Test
    fun openingReturningAndClosingTheWindowTwentyTimesWhileFramesArriveDrawsAndThrowsNothing() {
        org.jetbrains.skia.impl.Library.staticLoad()
        val exchange = FrameExchange()
        val pipeline = SmoothingPipeline(displayHz = { 60f })
        exchange.smoothing = pipeline
        pipeline.configure(SmoothnessSettings(mode = SmoothMode.BLEND))
        exchange.setVideoSize(320, 180)

        // The render thread's stand-in: the size the surfaces ask for, a grey frame, published, smoothing fed.
        val stop = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                while (!stop.get()) {
                    val target = exchange.target()
                    val width = FrameGeometry.widthOf(target).takeIf { it > 0 } ?: 160
                    val height = FrameGeometry.heightOf(target).takeIf { it > 0 } ?: 90
                    val buffer = exchange.beginWrite(width, height)
                    Pointer(buffer.address).setMemory(0L, (buffer.stride * buffer.height).toLong(), 0x80.toByte())
                    exchange.endWrite(buffer, published = true)
                    exchange.sourceFrame(buffer)
                    Thread.sleep(40)
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }.also { it.start() }

        val clock = longArrayOf(1_000_000_000L)
        val scene = ImageComposeScene(width = 320, height = 180, density = Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                // the page's player, and the picture-in-picture window's, of different sizes
                if (page) Box(Modifier.size(320.dp, 180.dp)) { FrameCanvas(exchange, Modifier.fillMaxSize(), VideoScale.FIT) }
                if (pip) Box(Modifier.size(160.dp, 90.dp)) { FrameCanvas(exchange, Modifier.fillMaxSize(), VideoScale.FIT) }
            }
        }
        try {
            settle(scene, clock, 6)
            repeat(20) {
                // open: the window draws too, the page lets go of the picture a moment later
                pip = true
                settle(scene, clock)
                page = false
                settle(scene, clock)
                // return: the window closes, the page takes the picture back
                pip = false
                settle(scene, clock)
                page = true
                settle(scene, clock)
            }
            // and the last one the other way round, page and window both for a few frames
            pip = true
            settle(scene, clock, 6)
            pip = false
            settle(scene, clock, 6)
            val image = scene.render(clock[0] + 16_000_000L)
            val bitmap = Bitmap.makeFromImage(image)
            val centre = bitmap.getColor(160, 90)
            assertEquals(0x80, (centre shr 16) and 0xff, "the picture is drawn after the last handover (red channel of the centre)")
        } finally {
            stop.set(true)
            writer.join(5_000)
            scene.close()
            pipeline.close()
        }
        assertNull(failure.get(), "the render thread threw: ${failure.get()}")
        assertNull(pipeline.readout.value.notice?.takeIf { "stopped after an error" in it }, "smoothing did not stop on an error")
        assertEquals(60f, exchange.display.hz(), "every surface let go of its window")
        assertTrue(!writer.isAlive)
    }
}
