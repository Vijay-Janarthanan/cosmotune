package com.opencanvas.core.reframer

import com.opencanvas.core.filter.FramingDecision
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/** Bounded frame sampling and inference; decoded buffers are never retained after submit returns. */
class FrameReframingWorker(
    scope: CoroutineScope,
    create: () -> SmartFrameReframer,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private data class Sample(val pixels: IntArray, val width: Int, val height: Int, val pts: Double)
    private val pending = Channel<Sample>(Channel.CONFLATED)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val mutable = MutableStateFlow(FramingDecision())
    val framing: StateFlow<FramingDecision> = mutable
    private var submitted = Double.NaN
    @Volatile private var interval = .25
    private val worker = scope.launch(Dispatchers.Default) {
        try {
            create().use { reframer ->
                for (sample in pending) {
                    val began = System.nanoTime()
                    mutable.value = reframer.analyze(sample.pixels, sample.width, sample.height, sample.pts)
                    // Keep inference below roughly a quarter of one CPU worker on slower devices.
                    interval = max(.25, (System.nanoTime() - began) / 1e9 * 4).coerceAtMost(1.0)
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            mutable.value = FramingDecision()
            log("frame analysis unavailable: ${failure.javaClass.simpleName}")
        } catch (failure: LinkageError) {
            mutable.value = FramingDecision()
            log("frame analysis runtime unavailable: ${failure.javaClass.simpleName}")
        } finally { closed.set(true); pending.close() }
    }

    @Synchronized private fun due(pts: Double): Boolean {
        if (closed.get() || !pts.isFinite() || (submitted.isFinite() && pts >= submitted && pts - submitted < interval)) return false
        submitted = pts
        return true
    }

    fun submitArgb(pixels: IntArray, width: Int, height: Int, ptsSec: Double) {
        if (width <= 0 || height <= 0 || pixels.size < width * height || !due(ptsSec)) return
        sample(width, height, ptsSec) { x, y -> pixels[y * width + x] }
    }

    fun submitBgra(pixels: ByteArray, width: Int, height: Int, ptsSec: Double) {
        if (width <= 0 || height <= 0 || pixels.size.toLong() < width.toLong() * height * 4 || !due(ptsSec)) return
        sample(width, height, ptsSec) { x, y ->
            val at = (y * width + x) * 4
            (255 shl 24) or ((pixels[at + 2].toInt() and 255) shl 16) or
                ((pixels[at + 1].toInt() and 255) shl 8) or (pixels[at].toInt() and 255)
        }
    }

    private inline fun sample(width: Int, height: Int, pts: Double, pixel: (Int, Int) -> Int) {
        val scale = min(1f, 640f / max(width, height))
        val w = max(1, (width * scale).roundToInt())
        val h = max(1, (height * scale).roundToInt())
        val output = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            output[y * w + x] = pixel((x / scale).toInt().coerceAtMost(width - 1), (y / scale).toInt().coerceAtMost(height - 1))
        }
        pending.trySend(Sample(output, w, h, pts))
    }

    override fun close() { if (closed.compareAndSet(false, true)) { pending.close(); worker.cancel() } }
}
