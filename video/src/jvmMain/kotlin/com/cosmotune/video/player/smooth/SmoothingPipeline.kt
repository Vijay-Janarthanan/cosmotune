package com.cosmotune.video.player.smooth

import com.cosmotune.video.player.desktop.FrameBuffer
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessReadout
import com.cosmotune.video.settings.SmoothnessSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Real-time frame smoothing for the desktop picture. mpv hands over each decoded frame as it is due (software render, so none
 * of mpv's own display-synchronised interpolation can run); this keeps the last two and draws in-between pictures at the
 * screen's rate instead of repeating the newer one.
 *
 * Three threads meet here, none of them waits for another:
 * - **The render thread** ([onSourceFrame], once per new frame): measures the frame rate, decides whether smoothing is in
 *   effect, copies the frame into the oldest of four snapshots, makes its small grey copy and publishes the new pair. For Smooth
 *   it hands the pair to the estimator.
 * - **The estimator** (its own thread): block matching on the two grey copies, then the field image the shader reads. A pair
 *   is answered at most once, and a search that is still running when the next pair comes makes that pair go without (and counts as
 *   too slow). A snapshot reused while a search read it is noticed by its stamp and the answer dropped.
 * - **The UI thread** ([draw], [vsync], [animating]): picks the phase from the clock, draws the pair, and measures its own time.
 *   While the field of a pair is not ready it shows the older frame, which is what phase 0 is anyway.
 *
 * Four snapshots in a ring mean the one written next is two frames older than the oldest the screen can be using. Nothing
 * here allocates per draw but Skia's own small shader objects.
 *
 * Latency: showing the picture between frame n-1 and n needs n, so the picture would trail the sound by one source frame.
 * [onAudioDelay] is asked to shift the sound by that much (mpv's `audio-delay`), and back to 0 when smoothing stops.
 */
internal class SmoothingPipeline(
    private val onAudioDelay: (seconds: Double) -> Unit = {},
    private val displayHz: () -> Float = DisplayRate::hz,
    private val nanos: () -> Long = System::nanoTime,
) : AutoCloseable {

    /** One copy of a source frame: the pixels, their small grey version and the Skia image over the pixels. */
    private class Snap(val frame: FrameBuffer, val luma: LumaPlane) {
        var image: Image? = null
    }

    /** The newer frame and the one before it, when the newer arrived, how long after the older, and a running number. */
    private class FramePair(val older: Snap?, val newer: Snap, val newerAt: Long, val gap: Long, val no: Long, val cut: Boolean)

    /** The motion of a pair as the field image, or that it is a scene cut. */
    private class Result(val no: Long, val field: Image, val sceneCut: Boolean)

    @Volatile private var settings = SmoothnessSettings()
    private val enabledFlow = MutableStateFlow(false)
    private val readoutFlow = MutableStateFlow(SmoothnessReadout.None)

    /** True while the settings ask for smoothing (the screen then keeps a per-frame clock running). */
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    /** What is truly happening, for the status chip. */
    val readout: StateFlow<SmoothnessReadout> = readoutFlow.asStateFlow()

    private val sourceMeter = FpsMeter()
    private val presentMeter = FpsMeter(window = 30, staleNanos = 1_000_000_000L)
    private val governor = SmoothGovernor()
    private val resolver = PresetResolver()
    private val estimator = MotionEstimator()

    @Volatile private var mode = SmoothMode.OFF
    @Volatile private var pair: FramePair? = null
    @Volatile private var result: Result? = null

    // The render thread's pool, under [poolLock] (the estimator and the screen only hold what a Pair gives them).
    private val poolLock = Any()
    private val slots = arrayOfNulls<Snap>(RING)
    private var ringIndex = 0
    private var extractor: LumaExtractor? = null
    private var counter = 0L

    // The estimator's state: one search at a time.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cosmotune-smooth").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val searching = AtomicBoolean(false)
    private var field: MotionField? = null
    private var codec: MotionFieldCodec? = null

    // The UI thread's.
    private var renderer: SmoothRenderer? = null
    private var lastOutputIndex = Long.MIN_VALUE
    private var lastReadoutAt = 0L
    @Volatile private var appliedDelay = 0.0
    @Volatile private var hz = 60f
    private var hzAt = Long.MIN_VALUE

    // ---- settings and lifecycle ---------------------------------------------------------------------------------------------

    /** Takes new settings (any thread). A different mode, target or preset gives a stepped-down mode its full ceiling back. */
    fun configure(next: SmoothnessSettings) {
        val old = settings
        settings = next
        enabledFlow.value = next.mode != SmoothMode.OFF
        if (old.mode != next.mode || old.targetFps != next.targetFps || old.preset != next.preset) governor.reset()
        if (next.mode == SmoothMode.OFF) {
            disengage()
            readoutFlow.value = SmoothnessReadout.None
        }
    }

    /** Something went wrong while handling a frame (out of memory, a missing native): smoothing stops and the plain frames carry on. */
    fun fail(error: Throwable) {
        System.err.println("Cosmotune video: frame smoothing stopped: ${error.javaClass.simpleName}: ${error.message}")
        governor.stop("Frame smoothing stopped after an error, so frames are shown as they come.")
        disengage()
        synchronized(poolLock) { releasePool() }
        readoutFlow.value = SmoothnessReadout(notice = governor.notice)
    }

    /** A new video or none: forget every measure and show nothing smoothed until the new video's rate is known. */
    fun reset() {
        sourceMeter.reset()
        presentMeter.reset()
        governor.reset()
        resolver.reset()
        disengage()
        readoutFlow.value = SmoothnessReadout.None
    }

    override fun close() {
        disengage()
        executor.shutdownNow()
        // The screen draws with the renderer's shaders under [poolLock]: they are closed under it too.
        synchronized(poolLock) {
            releasePool()
            renderer?.close()
            renderer = null
        }
    }

    private fun disengage() {
        mode = SmoothMode.OFF
        pair = null
        setAudioDelay(0.0)
    }

    private fun setAudioDelay(seconds: Double) {
        // A new value only when it matters (the gap between frames wobbles by a millisecond or two).
        if (seconds == 0.0) {
            if (appliedDelay == 0.0) return
        } else if (kotlin.math.abs(seconds - appliedDelay) < DELAY_STEP_S) {
            return
        }
        appliedDelay = seconds
        onAudioDelay(seconds)
    }

    // ---- the render thread ----------------------------------------------------------------------------------------------------

    /** A new frame was rendered into [buffer] at [at] (nanoseconds): measure, decide, and keep it as the newer of the pair. */
    fun onSourceFrame(buffer: FrameBuffer, at: Long) {
        val s = settings
        if (s.mode == SmoothMode.OFF) {
            if (pair != null || slots[0] != null) {
                disengage()
                synchronized(poolLock) { releasePool() }
            }
            return
        }
        sourceMeter.tick(at)
        val source = sourceMeter.fps(at)
        val output = SmoothPolicy.outputFps(s, currentHz(at))
        val effective = SmoothPolicy.effectiveMode(s, source, output, governor.ceiling)
        if (effective != mode) {
            if (effective == SmoothMode.OFF) {
                disengage()
                synchronized(poolLock) { releasePool() }
            }
            mode = effective
        }
        publishReadout(at, effective, source, output, force = true)
        if (effective == SmoothMode.OFF) return
        val interval = sourceMeter.intervalNanos(at) ?: return
        synchronized(poolLock) { ingest(buffer, at, interval, effective) }
    }

    private fun ingest(buffer: FrameBuffer, at: Long, interval: Long, effective: SmoothMode) {
        val width = buffer.width
        val height = buffer.height
        var lumaMaker = extractor
        val rebuilt = slots[0] == null || slots[0]!!.frame.width != width || slots[0]!!.frame.height != height
        if (rebuilt) {
            releasePool()
            val maker = LumaExtractor(width, height)
            lumaMaker = maker
            extractor = maker
            for (i in slots.indices) slots[i] = Snap(FrameBuffer(width, height), maker.newPlane())
            field = MotionField(maker.width / MotionEstimator.BLOCK, maker.height / MotionEstimator.BLOCK)
            codec = MotionFieldCodec(maker.width / MotionEstimator.BLOCK, maker.height / MotionEstimator.BLOCK, ENCODE_RANGE)
        }
        val slot = slots[ringIndex]!!
        ringIndex = (ringIndex + 1) % RING
        slot.image?.close()
        slot.image = null
        slot.frame.copyFrom(buffer)
        lumaMaker!!.extract(slot.frame.view(), slot.frame.stride, slot.luma)
        slot.image = Image.makeFromPixmap(slot.frame.pixmap)
        // A pool that was just rebuilt closed the images of the old pair: nothing of it may be drawn again.
        val old = if (rebuilt) null else pair
        val next = FramePair(
            older = old?.newer,
            newer = slot,
            newerAt = at,
            gap = if (old != null) at - old.newerAt else 0L,
            no = ++counter,
            // Two different shots must not dissolve into each other: a cut shows the newer frame at once (Smooth also finds it by its search).
            cut = old != null && MotionEstimator.meanAbsDiff(old.newer.luma.data, slot.luma.data) > CUT_MEAN_DIFF,
        )
        pair = next
        setAudioDelay(-(interval / 1e9).coerceAtMost(MAX_DELAY_S))
        if (effective == SmoothMode.SMOOTH && next.older != null && next.gap in 1..interval * 5 / 2) search(next, interval)
    }

    private fun releasePool() {
        for (i in slots.indices) {
            slots[i]?.image?.close()
            slots[i] = null
        }
        extractor = null
        ringIndex = 0
        result?.field?.close()
        result = null
    }

    private fun search(next: FramePair, interval: Long) {
        val budgetMs = interval / 1e6 * 0.9
        if (!searching.compareAndSet(false, true)) {
            // Still busy with the last pair: this one goes without a field, and that is a slow answer.
            governor.report(budgetMs * 2, budgetMs)
            return
        }
        try {
            executor.execute {
                try {
                    runSearch(next, budgetMs)
                } finally {
                    searching.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            searching.set(false)
        }
    }

    private fun runSearch(next: FramePair, budgetMs: Double) {
        val older = next.older ?: return
        val olderStamp = older.luma.stamp
        val newerStamp = next.newer.luma.stamp
        if (olderStamp and 1L == 1L || newerStamp and 1L == 1L) return
        val started = nanos()
        val out = field ?: return
        val maker = codec ?: return
        val preset = resolver.resolve(settings.preset)
        estimator.estimate(older.luma.data, next.newer.luma.data, older.luma.width, older.luma.height, SmoothPolicy.motionParams(preset, settings.strength), out)
        if (older.luma.stamp != olderStamp || next.newer.luma.stamp != newerStamp) return // a snapshot was reused under the search
        resolver.note(out.still)
        val image = maker.toImage(out)
        synchronized(poolLock) {
            val previous = result
            result = Result(next.no, image, out.sceneCut)
            previous?.field?.close()
        }
        governor.report((nanos() - started) / 1e6, budgetMs)
    }

    // ---- the UI thread --------------------------------------------------------------------------------------------------------

    /** True when the screen has a smoothed pair to draw (and so the plain frame is not drawn). */
    fun engaged(): Boolean = mode != SmoothMode.OFF && pair != null

    /**
     * Draws the pair at the phase [nowNanos] gives, into the box of [boxWidth] x [boxHeight], placed as the plain frame would be:
     * [src] (in pixels of a frame of [sourceWidth] x [sourceHeight]) goes to [dst]. False when there is nothing smoothed to draw,
     * and the caller draws the plain frame.
     */
    fun draw(canvas: Canvas, boxWidth: Float, boxHeight: Float, src: Rect, dst: Rect, sourceWidth: Int, sourceHeight: Int, nowNanos: Long): Boolean =
        // The render thread closes a snapshot's image when the ring reuses it, and the whole pool when smoothing stops; drawing
        // with an image that was closed meanwhile is a native crash, not an exception. Both happen under [poolLock].
        synchronized(poolLock) { drawLocked(canvas, boxWidth, boxHeight, src, dst, sourceWidth, sourceHeight, nowNanos) }

    private fun drawLocked(canvas: Canvas, boxWidth: Float, boxHeight: Float, src: Rect, dst: Rect, sourceWidth: Int, sourceHeight: Int, nowNanos: Long): Boolean {
        val current = pair ?: return false
        val active = mode
        if (active == SmoothMode.OFF) return false
        val newer = current.newer.image?.takeUnless { it.isClosed } ?: return false
        val started = nanos()
        try {
            paint(canvas, boxWidth, boxHeight, src, dst, sourceWidth, sourceHeight, nowNanos, current, active, newer)
        } catch (e: Throwable) {
            fail(e) // a shader that does not compile here, or Skia refusing: the plain frame goes on
            return false
        }
        presentMeter.tick(nowNanos)
        val periodMs = 1000.0 / SmoothPolicy.outputFps(settings, hz).coerceAtLeast(1f)
        governor.report((nanos() - started) / 1e6, periodMs * 0.8)
        return true
    }

    private fun paint(
        canvas: Canvas, boxWidth: Float, boxHeight: Float, src: Rect, dst: Rect, sourceWidth: Int, sourceHeight: Int, nowNanos: Long,
        current: FramePair, active: SmoothMode, newer: Image,
    ) {
        val painter = renderer ?: SmoothRenderer().also { renderer = it }
        val dest = SmoothRenderer.wholeImage(src, dst, sourceWidth, sourceHeight)
        val interval = sourceMeter.intervalNanos(nowNanos)
        val phase = if (interval == null) 1f else SmoothPolicy.phase(nowNanos, current.newerAt, current.gap, interval)
        val older = current.older?.image?.takeUnless { it.isClosed }
        val s = settings
        canvas.save()
        try {
            canvas.clipRect(Rect.makeWH(boxWidth, boxHeight))
            if (older == null || phase >= 1f || current.cut) {
                painter.drawSingle(canvas, newer, dest)
            } else if (active == SmoothMode.BLEND) {
                painter.drawBlend(canvas, older, newer, dest, SmoothPolicy.blendWeight(phase, s.strength))
            } else {
                val answer = result
                when {
                    answer == null || answer.no != current.no -> painter.drawSingle(canvas, older, dest)
                    answer.sceneCut -> painter.drawSingle(canvas, newer, dest)
                    else -> painter.drawWarp(
                        canvas, older, newer, answer.field, dest, phase, ENCODE_RANGE,
                        current.newer.luma.width, current.newer.luma.height, SmoothPolicy.mismatchBias(s.strength),
                    )
                }
            }
        } finally {
            canvas.restore()
        }
    }

    /** Whether the screen should draw on this frame of its clock: always at the display's rate, else at the chosen target's. */
    fun vsync(nowNanos: Long): Boolean {
        publishReadout(nowNanos, mode, sourceMeter.fps(nowNanos), SmoothPolicy.outputFps(settings, currentHz(nowNanos)), force = false)
        val index = SmoothPolicy.outputIndex(nowNanos, settings.targetFps)
        if (index == lastOutputIndex) return false
        lastOutputIndex = index
        return true
    }

    /** Whether a frame is still on its way between two source frames (then the screen draws every frame; else it rests). */
    fun animating(nowNanos: Long): Boolean {
        if (mode == SmoothMode.OFF) return false
        val current = pair ?: return false
        val interval = sourceMeter.intervalNanos(nowNanos) ?: return false
        return nowNanos - current.newerAt < interval + TAIL_NANOS
    }

    /** Suspends until [animating] (checked again whenever [frames] moves: a new frame or a clear). */
    suspend fun awaitAnimation(frames: StateFlow<Long>) {
        while (true) {
            val seen = frames.value
            if (animating(nanos())) return
            frames.first { it != seen }
        }
    }

    // ---- the readout ----------------------------------------------------------------------------------------------------------

    private fun publishReadout(now: Long, effective: SmoothMode, source: Float?, output: Float, force: Boolean) {
        if (!force && now - lastReadoutAt < READOUT_EVERY_NANOS) return
        lastReadoutAt = now
        val s = settings
        if (s.mode == SmoothMode.OFF) return
        val presented = if (effective == SmoothMode.OFF) null else presentMeter.fps(now)
        val notice = governor.notice ?: SmoothPolicy.whyNot(s, source, output, effective)
        readoutFlow.value = SmoothnessReadout(
            mode = effective,
            sourceFps = source?.let { (it * 10).roundToInt() / 10f },
            presentedFps = presented?.roundToInt()?.toFloat(),
            notice = notice,
        )
    }

    private fun currentHz(now: Long): Float {
        if (hzAt == Long.MIN_VALUE || now - hzAt > HZ_EVERY_NANOS) {
            hzAt = now
            hz = displayHz()
        }
        return hz
    }

    private companion object {
        /** Snapshots kept: the pair, the one being written, and one more of slack. */
        const val RING = 4

        /** The mean grey difference of two whole frames above which they are different shots. */
        const val CUT_MEAN_DIFF = 45f

        /** The field image is scaled so that this many analysis pixels fill the byte range (the widest search plus a fraction). */
        const val ENCODE_RANGE = 13f

        /** The sound is never moved by more than this (a 10 fps video would want 100 ms). */
        const val MAX_DELAY_S = 0.1
        const val DELAY_STEP_S = 0.004

        /** The screen keeps drawing this long after the phase reached the newer frame, so the last frame drawn is the newer one itself. */
        const val TAIL_NANOS = 60_000_000L
        const val READOUT_EVERY_NANOS = 500_000_000L
        const val HZ_EVERY_NANOS = 5_000_000_000L
    }
}
