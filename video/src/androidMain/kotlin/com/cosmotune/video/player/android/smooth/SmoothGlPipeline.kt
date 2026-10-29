package com.cosmotune.video.player.android.smooth

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.cosmotune.video.local.AspectMath
import com.cosmotune.video.local.VideoAspect
import com.cosmotune.video.player.smooth.DisplayRates
import com.cosmotune.video.player.smooth.FpsMeter
import com.cosmotune.video.player.smooth.FramePlanner
import com.cosmotune.video.player.smooth.GlslSources
import com.cosmotune.video.player.smooth.MotionEstimator
import com.cosmotune.video.player.smooth.SmoothPolicy
import com.cosmotune.video.player.smooth.SmoothSchedule
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessReadout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The phone's frame smoothing, on the GPU, with no readback of the picture.
 *
 * Media3 decodes into [Listener.onInputSurface] (a `SurfaceTexture` made here). Each decoded frame is copied into one of a ring of
 * textures, and (for Blend and Smooth) a small grey copy of it is made. Smooth compares that with the grey copy of the frame
 * before: one pass takes the sum of absolute differences of every 8 x 8 block against every displacement within the search
 * range, one pass takes the best of each block (with the desktop's tests: a flat block stands still, noise is not motion, the
 * parabola refines it) and writes the motion field, one pass adds the blocks up into a pixel of flags (still, scene cut). Blend
 * needs only the difference at rest, for the cut. At the screen's own pace (`Choreographer`) the output window is drawn with
 * the pair on screen: the older frame blended into the newer, or the older and newer warped along the field, the same shader as
 * the desktop's.
 *
 * The picture shown trails the newest frame by one source interval, since the frame between two needs the later of them; the
 * engine holds the sound back by the same interval ([SmoothingHub.holdAudio]).
 *
 * **Threads.** Everything (EGL, every GL call, the frame callback, the refresh callback) runs on one `HandlerThread`; the methods
 * below that say "any thread" only post to it. **Failure.** Any exception, a shader that does not compile or link, a missing
 * extension or a framebuffer that is not complete ends the pipeline: [Listener.onFailed] is told once, nothing more is drawn and
 * the host takes the picture back to the plain surface.
 */
internal class SmoothGlPipeline(private val hub: SmoothingHub, private val listener: Listener) {

    interface Listener {
        /** The surface the decoder is to draw to (GL thread). */
        fun onInputSurface(surface: Surface)

        /** The pipeline stopped for [reason] (GL thread); said once. */
        fun onFailed(reason: String)
    }

    private enum class Analysis { NONE, ZERO, FULL }

    /** One frame of the ring: the picture, its small grey copy, and what was found out about it against the frame before. */
    private class Slot {
        var color: GlTarget? = null
        var luma: GlTarget? = null
        var motion: GlTarget? = null
        var flags: GlTarget? = null
        var lumaReady = false
        var kind = Analysis.NONE
        var fence = 0L
        var fenceAt = 0L
        var resolved = true
        var slowReported = false
        var cut = false
        var hasField = false
        var time = 0L
        var pred = -1

        fun reset() {
            if (fence != 0L) GLES30.glDeleteSync(fence)
            fence = 0L
            lumaReady = false
            kind = Analysis.NONE
            resolved = true
            slowReported = false
            cut = false
            hasField = false
            pred = -1
        }

        fun delete() {
            reset()
            color?.delete(); luma?.delete(); motion?.delete(); flags?.delete()
            color = null; luma = null; motion = null; flags = null
        }
    }

    private val thread = HandlerThread("cosmotune-smooth-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var closing = false

    @Volatile private var aspect = VideoAspect.FIT

    // ---- GL-thread state ----

    private val egl = EglCore()
    private val programs = HashMap<String, GlProgram>()
    private var searchOk = false

    /** Whether Smooth can run: the search passes built, and the table of sums fits a texture at this size. */
    private var smoothOk = false
    private var started = false
    private var failed = false
    private var vbo = 0
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private var window: EGLSurface? = null
    private var outWidth = 0
    private var outHeight = 0
    private var choreographer: Choreographer? = null
    private var tickScheduled = false
    private var maxTexture = 2048
    private var glErrors = 0

    private val planner = FramePlanner(RING)
    private val slots = Array(RING) { Slot() }
    private var frameWidth = 0
    private var frameHeight = 0
    private var lumaWidth = 0
    private var lumaHeight = 0
    private var cols = 0
    private var rows = 0
    private var statTexture = 0
    private var statTarget: GlTarget? = null
    private var sadTarget: GlTarget? = null
    private var sadRange = -1
    private val readback: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private val sourceMeter = FpsMeter()
    private val presentMeter = FpsMeter(window = 30, staleNanos = 1_000_000_000L)
    private var mode = SmoothMode.OFF
    private var lastStamp = Long.MIN_VALUE
    private var lastTime = Long.MIN_VALUE
    private var generation = -1
    private var lastOutputIndex = Long.MIN_VALUE
    private var lastReadoutAt = 0L
    private var offFrames = 0
    private var capNotice: String? = null
    private var sizeRetries = 0
    private var attachedOnce = false

    private val tick = Choreographer.FrameCallback { frameTime -> guarded("frame") { doFrame(frameTime) } }

    // ---- any thread ----

    /** Starts the GL thread: the context, the programs, the surface for the decoder. */
    fun start() {
        handler.post { guarded("start") { init() } }
    }

    /** The output window is [surface], [width] x [height] pixels (it was created, or its size changed). */
    fun setOutput(surface: Surface, width: Int, height: Int) {
        handler.post { guarded("output") { attachOutput(surface, width, height) } }
    }

    /** How the picture sits in the output (fit, fill, a forced shape). */
    fun setAspect(value: VideoAspect) {
        aspect = value
        handler.post { guarded("aspect") { redraw() } }
    }

    /** Lets go of the output window and waits for it (the window system will not take a destroyed surface back otherwise). */
    fun clearOutput() {
        val done = CountDownLatch(1)
        if (!handler.post { try { guarded("clear output") { detachOutput() } } finally { done.countDown() } }) return
        done.await(OUTPUT_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Ends the pipeline: the output is let go of at once (waited for); the rest waits a moment, since the player may still be
     * drawing to the decoder's surface until it has taken it away, and that surface must outlive its last frame.
     */
    fun release() {
        if (closing) return
        closing = true
        clearOutput()
        handler.post { guarded("stop") { stopDrawing() } }
        handler.postDelayed({ teardown() }, DRAIN_MS)
    }

    // ---- start ----

    private fun init() {
        if (closing) return
        egl.create()
        val gles = egl.glesVersion
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
        Log.i(SMOOTH_TAG, "OpenGL ES $gles on $renderer")
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!extensions.contains("GL_OES_EGL_image_external")) throw GlException("GL_OES_EGL_image_external is missing")
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        maxTexture = max[0].coerceAtLeast(2048)
        for (source in GlslSources.ES2_PROGRAMS) programs[source.name] = GlProgram.build(source)
        searchOk = false
        smoothOk = true
        if (gles >= 3) {
            try {
                for (source in GlslSources.ES3_PROGRAMS) programs[source.name] = GlProgram.build(source)
                searchOk = true
            } catch (e: GlException) {
                // The picture passes still work: Blend runs without the cut test, Smooth falls back to Blend.
                Log.w(SMOOTH_TAG, "the search passes are not available: ${e.message}")
                capNotice = "Smooth is not available on this phone's graphics, so Blend is used instead."
            }
        } else {
            capNotice = "Smooth needs OpenGL ES 3.0, so Blend is used instead."
        }
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, quad, GLES20.GL_STATIC_DRAW)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("setup")

        val texture = SurfaceTexture(oesTexture)
        surfaceTexture = texture
        texture.setOnFrameAvailableListener({ guarded("input") { ingest() } }, handler)
        val surface = Surface(texture)
        inputSurface = surface
        choreographer = Choreographer.getInstance()
        started = true
        listener.onInputSurface(surface)
    }

    // ---- the output ----

    private fun attachOutput(surface: Surface, width: Int, height: Int) {
        if (closing || failed || !started) return
        if (!surface.isValid) return
        val existing = window
        if (existing == null) {
            // A window that was destroyed while this was on its way is not an error: the host sends the next one.
            val created = try {
                egl.createWindowSurface(surface)
            } catch (e: GlException) {
                Log.w(SMOOTH_TAG, "the output window could not be used: ${e.message}")
                return
            }
            window = created
            egl.makeCurrent(created)
            egl.setSwapInterval(1)
            if (!attachedOnce) Log.i(SMOOTH_TAG, "output ${width}x$height")
            attachedOnce = true
        }
        outWidth = width
        outHeight = height
        redraw()
    }

    private fun detachOutput() {
        val ws = window ?: return
        window = null
        egl.destroySurface(ws)
        egl.makeIdleCurrent()
    }

    /** Draws the newest frame once (a new output, a new size or shape, a pause). */
    private fun redraw() {
        if (closing || failed || window == null) return
        val period = DisplayRates.periodNanos(hub.currentHz())
        val shown = planner.select(SmoothSchedule.displayTime(System.nanoTime(), period)) ?: return
        drawSingle(slots[shown.newer])
        swap(0L)
    }

    // ---- a new frame ----

    private fun ingest() {
        if (closing || failed || !started) return
        val texture = surfaceTexture ?: return
        if (hub.videoWidth <= 0 || hub.videoHeight <= 0) {
            // The size of the video is not known yet (it is told just before the first frame): the frame stays queued.
            if (sizeRetries++ < SIZE_RETRIES) handler.postDelayed({ guarded("input") { ingest() } }, SIZE_RETRY_MS)
            return
        }
        sizeRetries = 0
        checkGeneration()
        makeWorkCurrent()
        texture.updateTexImage()
        val stamp = texture.timestamp
        if (stamp == lastStamp) return // the same frame again
        lastStamp = stamp
        texture.getTransformMatrix(texMatrix)
        val arrival = System.nanoTime()
        val time = SmoothSchedule.frameTime(stamp, arrival, lastTime)
        lastTime = time
        ensureRing()

        // what smoothing does with this video, from the rate the frames really come at
        sourceMeter.tick(time)
        val source = sourceMeter.fps(time)
        val settings = hub.settings
        val output = SmoothPolicy.outputFps(settings, DisplayRates.displayHzFor(settings, hub.currentHz(), hub.ceilingHz()))
        var effective = SmoothPolicy.effectiveMode(settings, source, output, hub.governor.ceiling)
        if (effective == SmoothMode.SMOOTH && !(searchOk && smoothOk)) effective = SmoothMode.BLEND
        if (effective != mode) changeMode(effective)
        publishReadout(time, source, output, force = true)
        if (effective == SmoothMode.OFF) {
            offFrames++
            if (source != null && hub.governor.ceiling != SmoothMode.OFF && offFrames == DECLINE_AFTER_FRAMES) {
                hub.declineForVideo(SmoothPolicy.whyNot(settings, source, output, SmoothMode.OFF))
            }
        } else {
            offFrames = 0
        }
        val interval = sourceMeter.intervalNanos(time)
        if (effective != SmoothMode.OFF && interval != null) hub.holdAudio(interval)

        var index = planner.acquire()
        if (index < 0) {
            val dropped = planner.dropOldestWaiting()
            if (dropped >= 0) slots[dropped].reset()
            index = planner.acquire()
        }
        if (index < 0) return
        val slot = slots[index]
        slot.reset()
        slot.time = time
        renderExternal(slot)
        if (effective != SmoothMode.OFF && searchOk) {
            renderLuma(slot)
            slot.lumaReady = true
            val previous = if (planner.last >= 0) slots[planner.last] else null
            if (previous != null && previous.lumaReady && interval != null && time - previous.time in 1..interval * 5 / 2) {
                analyse(slot, previous, planner.last, effective, interval)
            }
        }
        planner.offer(index, time)
        checkGl("frame")
        if (effective == SmoothMode.OFF) {
            // nothing to smooth: the frame goes out as it comes, held until its own time where the compositor can
            val shown = planner.showNewest() ?: return
            if (window != null) {
                drawSingle(slots[shown.newer])
                swap(time)
            }
        } else {
            scheduleTick()
        }
    }

    /** The context is current on the output window when there is one, else on the idle surface. */
    private fun makeWorkCurrent() {
        val ws = window
        if (ws != null) egl.makeCurrent(ws) else egl.makeIdleCurrent()
    }

    private fun checkGeneration() {
        val now = hub.generation.get()
        if (now == generation) return
        generation = now
        planner.clear()
        for (slot in slots) slot.reset()
        sourceMeter.reset()
        presentMeter.reset()
        mode = SmoothMode.OFF
        lastStamp = Long.MIN_VALUE
        lastTime = Long.MIN_VALUE
        lastOutputIndex = Long.MIN_VALUE
        offFrames = 0
    }

    private fun changeMode(next: SmoothMode) {
        mode = next
        if (next == SmoothMode.OFF) {
            hub.holdAudio(null)
            // the pair on screen is the newest frame again at once
        }
        if (hub.governor.ceiling == SmoothMode.OFF) hub.recompute()
    }

    /** Makes the ring (and the analysis targets) for the video's size when there is none or the size changed. */
    private fun ensureRing() {
        val size = SmoothSchedule.frameSize(hub.videoWidth, hub.videoHeight)
        if (size[0] == frameWidth && size[1] == frameHeight && slots[0].color != null) return
        releaseRing()
        frameWidth = size[0]
        frameHeight = size[1]
        planner.clear()
        val analysis = SmoothSchedule.analysisSize(frameWidth, frameHeight)
        lumaWidth = analysis[0]
        lumaHeight = analysis[1]
        cols = lumaWidth / MotionEstimator.BLOCK
        rows = lumaHeight / MotionEstimator.BLOCK
        smoothOk = true
        if (searchOk) {
            statTexture = GlTarget.newTexture(cols, rows, linear = false)
            statTarget = GlTarget.around(cols, rows, statTexture)
        }
        for (slot in slots) {
            slot.color = GlTarget.create(frameWidth, frameHeight, linear = true)
            if (searchOk) {
                slot.luma = GlTarget.create(lumaWidth, lumaHeight, linear = false)
                slot.motion = GlTarget.createWithExtra(cols, rows, linear = true, extra = statTexture)
                slot.flags = GlTarget.create(1, 1, linear = false)
            }
        }
        checkGl("ring")
    }

    private fun releaseRing() {
        planner.clear()
        for (slot in slots) slot.delete()
        statTarget?.delete()
        statTarget = null
        if (statTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(statTexture), 0)
        statTexture = 0
        sadTarget?.delete()
        sadTarget = null
        sadRange = -1
    }

    // ---- the passes ----

    private fun use(program: GlProgram) {
        GLES20.glUseProgram(program.id)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
    }

    private fun program(name: String): GlProgram = programs[name] ?: throw GlException("no program $name")

    private fun aim(target: GlTarget) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo)
        GLES20.glViewport(0, 0, target.width, target.height)
    }

    private fun bind(unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    private fun place(program: GlProgram, x0: Float, y0: Float, x1: Float, y1: Float, matrix: FloatArray = IDENTITY) {
        GLES20.glUniform4f(program.uniform("uPlace"), x0, y0, x1, y1)
        GLES20.glUniformMatrix4fv(program.uniform("uTexMatrix"), 1, false, matrix, 0)
    }

    private fun quad() = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

    /** The decoder's frame into [slot]'s picture. */
    private fun renderExternal(slot: Slot) {
        val p = program("oes")
        use(p)
        aim(slot.color!!)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(p.uniform("uTex"), 0)
        place(p, -1f, -1f, 1f, 1f, texMatrix)
        quad()
    }

    /** [slot]'s picture into its small grey copy. */
    private fun renderLuma(slot: Slot) {
        val p = program("luma")
        use(p)
        aim(slot.luma!!)
        bind(0, slot.color!!.texture)
        GLES20.glUniform1i(p.uniform("uTex"), 0)
        GLES20.glUniform2f(p.uniform("uCell"), 1f / lumaWidth, 1f / lumaHeight)
        place(p, -1f, -1f, 1f, 1f)
        quad()
    }

    /** The comparison of [cur] with [prev] ([prevIndex]): the field (Smooth) and the flags, with a fence to ask whether they are done. */
    private fun analyse(cur: Slot, prev: Slot, prevIndex: Int, effective: SmoothMode, interval: Long) {
        val settings = hub.settings
        val preset = hub.resolver.resolve(settings.preset)
        val params = SmoothPolicy.motionParams(preset, settings.strength)
        var full = effective == SmoothMode.SMOOTH
        if (full && !ensureSearch(params.range)) {
            full = false
            smoothOk = false
            capNotice = "Smooth does not fit this phone's graphics at this size, so Blend is used instead."
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        if (full) {
            val sad = sadTarget!!
            val p = program("sad")
            use(p)
            aim(sad)
            bind(0, cur.luma!!.texture)
            bind(1, prev.luma!!.texture)
            GLES20.glUniform1i(p.uniform("uCur"), 0)
            GLES20.glUniform1i(p.uniform("uPrev"), 1)
            GLES20.glUniform2i(p.uniform("uLuma"), lumaWidth, lumaHeight)
            GLES20.glUniform1i(p.uniform("uRange"), params.range)
            quad()

            val s = program("select")
            use(s)
            aim(cur.motion!!)
            bind(0, sad.texture)
            GLES20.glUniform1i(s.uniform("uSad"), 0)
            GLES20.glUniform1i(s.uniform("uRange"), params.range)
            GLES20.glUniform1f(s.uniform("uTolerance"), params.tolerance)
            GLES20.glUniform1f(s.uniform("uMotionRange"), GlslSources.MOTION_RANGE)
            quad()
        } else {
            val z = program("zero")
            use(z)
            aim(statTarget!!)
            bind(0, cur.luma!!.texture)
            bind(1, prev.luma!!.texture)
            GLES20.glUniform1i(z.uniform("uCur"), 0)
            GLES20.glUniform1i(z.uniform("uPrev"), 1)
            quad()
        }
        val r = program("reduce")
        use(r)
        aim(cur.flags!!)
        bind(0, statTexture)
        GLES20.glUniform1i(r.uniform("uStat"), 0)
        GLES20.glUniform2i(r.uniform("uBlocks"), cols, rows)
        GLES20.glUniform1f(r.uniform("uTolerance"), params.tolerance)
        GLES20.glUniform1f(r.uniform("uCutFraction"), params.cutFraction)
        GLES20.glUniform1f(r.uniform("uCutMad"), params.cutMad)
        quad()
        cur.kind = if (full) Analysis.FULL else Analysis.ZERO
        cur.pred = prevIndex
        cur.resolved = false
        cur.fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        cur.fenceAt = System.nanoTime()
        GLES20.glFlush()
    }

    /** Makes the table the search writes its sums into, for a search of [range] pixels; false when it would not fit a texture. */
    private fun ensureSearch(range: Int): Boolean {
        if (sadTarget != null && sadRange == range) return true
        val side = 2 * range + 1
        if (cols * side > maxTexture || rows * side > maxTexture) return false
        sadTarget?.delete()
        sadTarget = GlTarget.create(cols * side, rows * side, linear = false)
        sadRange = range
        return true
    }

    /**
     * Reads what the comparison of [slot] found, once the GPU is done with it. False while it is not (the pair then shows its
     * older frame, which is where it starts anyway); a pair that was never compared is ready with nothing found.
     */
    private fun resolve(slot: Slot, interval: Long?): Boolean {
        if (slot.resolved) return true
        val now = System.nanoTime()
        val elapsedMs = (now - slot.fenceAt) / 1e6
        val budgetMs = (interval ?: DEFAULT_INTERVAL_NANOS) / 1e6 * 0.9
        val tickMs = DisplayRates.periodNanos(hub.currentHz()) / 1e6
        val status = GLES30.glClientWaitSync(slot.fence, 0, 0L)
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
            if (status == GLES30.GL_WAIT_FAILED) throw GlException("waiting for the search failed")
            if (!slot.slowReported && elapsedMs > budgetMs * 2) {
                slot.slowReported = true
                if (hub.governor.report(elapsedMs, budgetMs)) hub.recompute()
            }
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.flags!!.fbo)
        readback.clear()
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        val still = (readback.get(0).toInt() and 0xff) > 127
        val searchCut = (readback.get(1).toInt() and 0xff) > 127
        val plainCut = (readback.get(2).toInt() and 0xff) > 127
        slot.cut = plainCut || (slot.kind == Analysis.FULL && searchCut)
        slot.hasField = slot.kind == Analysis.FULL
        slot.resolved = true
        GLES30.glDeleteSync(slot.fence)
        slot.fence = 0L
        if (slot.kind == Analysis.FULL) hub.resolver.note(still)
        if (!slot.slowReported && hub.governor.report(SmoothSchedule.gpuWorkMs(elapsedMs, tickMs), budgetMs)) hub.recompute()
        return true
    }

    // ---- drawing ----

    private fun scheduleTick() {
        if (tickScheduled || closing || failed || window == null) return
        tickScheduled = true
        choreographer?.postFrameCallback(tick)
    }

    private fun doFrame(frameTimeNanos: Long) {
        tickScheduled = false
        if (closing || failed) return
        val begun = System.nanoTime()
        val settings = hub.settings
        val period = DisplayRates.periodNanos(hub.currentHz())
        val target = SmoothSchedule.displayTime(frameTimeNanos, period)
        publishReadout(frameTimeNanos, sourceMeter.fps(frameTimeNanos), SmoothPolicy.outputFps(settings, DisplayRates.displayHzFor(settings, hub.currentHz(), hub.ceilingHz())), force = false)
        if (mode == SmoothMode.OFF || window == null) return
        val shown = planner.select(target) ?: return
        val interval = sourceMeter.intervalNanos(target)
        val index = SmoothPolicy.outputIndex(frameTimeNanos, settings.targetFps)
        if (index != lastOutputIndex) {
            lastOutputIndex = index
            drawPair(shown, target, interval, settings.strength)
            if (swap(0L)) {
                presentMeter.tick(frameTimeNanos)
                val budgetMs = 1000.0 / SmoothPolicy.outputFps(settings, hub.currentHz()).coerceAtLeast(1f) * 0.8
                if (hub.governor.report((System.nanoTime() - begun) / 1e6, budgetMs)) hub.recompute()
            }
        }
        if (SmoothSchedule.animating(target, shown.newerAt, interval, planner.pending > 0)) scheduleTick()
    }

    private fun drawPair(shown: FramePlanner.Shown, target: Long, interval: Long?, strength: Int) {
        val newer = slots[shown.newer]
        val older = if (shown.older >= 0) slots[shown.older] else null
        val phase = if (interval == null) 1f else SmoothPolicy.phase(target, shown.newerAt, shown.gap, interval)
        val ready = resolve(newer, interval)
        when {
            older == null || phase >= 1f -> drawSingle(newer)
            !ready -> drawSingle(older)
            newer.cut -> drawSingle(newer)
            mode == SmoothMode.BLEND -> {
                val weight = SmoothPolicy.blendWeight(phase, strength)
                when {
                    weight >= 0.995f -> drawSingle(newer)
                    weight <= 0.005f -> drawSingle(older)
                    else -> drawBlend(older, newer, weight)
                }
            }
            newer.hasField && newer.pred == shown.older -> drawWarp(older, newer, phase, strength)
            else -> drawSingle(older)
        }
    }

    /** Aims at the output window and clears it; the pictures below fill the rectangle the shape asks for. */
    private fun beginOutput() {
        val ws = window ?: return
        egl.makeCurrent(ws)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** The rectangle (in clip space) the whole frame is drawn into: centred, the size [aspect] gives for the box. */
    private fun placement(out: FloatArray) {
        val videoWidth = hub.videoWidth * hub.pixelRatio
        val drawn = AspectMath.drawnSize(aspect, outWidth.toFloat(), outHeight.toFloat(), videoWidth, hub.videoHeight.toFloat())
        val sx = if (outWidth > 0) drawn.width / outWidth else 1f
        val sy = if (outHeight > 0) drawn.height / outHeight else 1f
        out[0] = -sx; out[1] = -sy; out[2] = sx; out[3] = sy
    }

    private val rect = FloatArray(4)

    private fun drawSingle(slot: Slot) {
        if (window == null) return
        beginOutput()
        val p = program("copy")
        use(p)
        placement(rect)
        place(p, rect[0], rect[1], rect[2], rect[3])
        bind(0, slot.color!!.texture)
        GLES20.glUniform1i(p.uniform("uTex"), 0)
        quad()
    }

    private fun drawBlend(older: Slot, newer: Slot, weight: Float) {
        beginOutput()
        val p = program("blend")
        use(p)
        placement(rect)
        place(p, rect[0], rect[1], rect[2], rect[3])
        bind(0, older.color!!.texture)
        bind(1, newer.color!!.texture)
        GLES20.glUniform1i(p.uniform("uOlder"), 0)
        GLES20.glUniform1i(p.uniform("uNewer"), 1)
        GLES20.glUniform1f(p.uniform("uWeight"), weight)
        quad()
    }

    private fun drawWarp(older: Slot, newer: Slot, phase: Float, strength: Int) {
        beginOutput()
        val p = program("warp")
        use(p)
        placement(rect)
        place(p, rect[0], rect[1], rect[2], rect[3])
        bind(0, older.color!!.texture)
        bind(1, newer.color!!.texture)
        bind(2, newer.motion!!.texture)
        GLES20.glUniform1i(p.uniform("uOlder"), 0)
        GLES20.glUniform1i(p.uniform("uNewer"), 1)
        GLES20.glUniform1i(p.uniform("uMotion"), 2)
        GLES20.glUniform1f(p.uniform("uPhase"), phase.coerceIn(0f, 1f))
        GLES20.glUniform2f(p.uniform("uInvLuma"), 1f / lumaWidth, 1f / lumaHeight)
        GLES20.glUniform1f(p.uniform("uMotionRange"), GlslSources.MOTION_RANGE)
        GLES20.glUniform1f(p.uniform("uBias"), SmoothPolicy.mismatchBias(strength).coerceIn(0f, 1f))
        quad()
    }

    /** Presents what was drawn. False when the window is gone (it is let go of; the host sends a new one). */
    private fun swap(presentationNanos: Long): Boolean {
        val ws = window ?: return false
        if (presentationNanos > 0) egl.setPresentationTime(ws, presentationNanos)
        if (egl.swap(ws)) return true
        val error = EGL14.eglGetError()
        if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
            Log.w(SMOOTH_TAG, "the output window is gone (0x${Integer.toHexString(error)})")
            detachOutput()
            return false
        }
        throw GlException("eglSwapBuffers failed: 0x" + Integer.toHexString(error))
    }

    // ---- the readout ----

    private fun publishReadout(now: Long, source: Float?, output: Float, force: Boolean) {
        if (!force && now - lastReadoutAt < READOUT_EVERY_NANOS) return
        lastReadoutAt = now
        val settings = hub.settings
        if (settings.mode == SmoothMode.OFF) return
        val presented = if (mode == SmoothMode.OFF) null else presentMeter.fps(now)
        val notice = hub.governor.notice
            ?: (if (settings.mode == SmoothMode.SMOOTH && mode != SmoothMode.SMOOTH) capNotice else null)
            ?: SmoothPolicy.whyNot(settings, source, output, mode)
        hub.publish(
            SmoothnessReadout(
                mode = mode,
                sourceFps = source?.let { (it * 10).roundToInt() / 10f },
                presentedFps = presented?.roundToInt()?.toFloat(),
                notice = notice,
            ),
        )
    }

    // ---- errors and the end ----

    /** Counts GL errors (a few are survived, a stream of them is not). */
    private fun checkGl(where: String) {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) {
            glErrors = 0
            return
        }
        Log.w(SMOOTH_TAG, "GL error 0x${Integer.toHexString(error)} after $where")
        if (error == GLES20.GL_OUT_OF_MEMORY || ++glErrors >= MAX_GL_ERRORS) throw GlException("GL error 0x${Integer.toHexString(error)} after $where")
    }

    private inline fun guarded(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(label, t)
        }
    }

    private fun fail(label: String, t: Throwable) {
        if (failed) return
        failed = true
        Log.e(SMOOTH_TAG, "frame smoothing failed ($label)", t)
        choreographer?.removeFrameCallback(tick)
        tickScheduled = false
        hub.holdAudio(null)
        listener.onFailed("$label: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** No more drawing and no more analysis; frames that still arrive are taken off the texture and dropped. */
    private fun stopDrawing() {
        choreographer?.removeFrameCallback(tick)
        tickScheduled = false
        hub.holdAudio(null)
        surfaceTexture?.setOnFrameAvailableListener({ drain() }, handler)
    }

    private fun drain() {
        try {
            egl.makeIdleCurrent()
            surfaceTexture?.updateTexImage()
        } catch (_: Throwable) {
            // being taken down; nothing to do
        }
    }

    private fun teardown() {
        try {
            choreographer?.removeFrameCallback(tick)
            surfaceTexture?.setOnFrameAvailableListener(null)
            try {
                detachOutput()
                if (started) {
                    egl.makeIdleCurrent()
                    releaseRing()
                    for (p in programs.values) p.delete()
                    programs.clear()
                    if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
                    if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                }
            } catch (t: Throwable) {
                Log.w(SMOOTH_TAG, "releasing the GL resources failed: $t")
            }
            inputSurface?.release()
            surfaceTexture?.release()
            inputSurface = null
            surfaceTexture = null
            egl.release()
        } catch (t: Throwable) {
            Log.w(SMOOTH_TAG, "teardown failed: $t")
        } finally {
            thread.quitSafely()
        }
    }

    private companion object {
        /** Frames kept: the pair on screen, the ones waiting for their turn, the one being written. */
        const val RING = 6
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        const val SIZE_RETRIES = 40
        const val SIZE_RETRY_MS = 50L
        const val OUTPUT_WAIT_MS = 500L

        /** How long the decoder's surface outlives the pipeline, for the player to have taken it away. */
        const val DRAIN_MS = 1_500L
        const val DEFAULT_INTERVAL_NANOS = 41_666_666L

        /** Frames in a row that smoothing had nothing to do for before the picture is handed back to the plain path. */
        const val DECLINE_AFTER_FRAMES = 48
        const val MAX_GL_ERRORS = 5
        const val READOUT_EVERY_NANOS = 500_000_000L
    }
}
