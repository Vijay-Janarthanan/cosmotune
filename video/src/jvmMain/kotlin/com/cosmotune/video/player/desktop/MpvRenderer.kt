package com.cosmotune.video.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.cosmotune.video.local.AspectMath
import com.cosmotune.video.local.VideoAspect
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.smooth.DisplayRateSource
import com.cosmotune.video.player.smooth.LocalVideoHostWindow
import com.cosmotune.video.player.smooth.SmoothingClock
import com.cosmotune.video.player.smooth.SmoothingPipeline
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_API_TYPE_SW
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_API_TYPE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_INVALID
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_SW_FORMAT
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_SW_POINTER
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_SW_SIZE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_PARAM_SW_STRIDE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_RENDER_UPDATE_FRAME
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The desktop engine's frames (spec 6.3, plan task Y-12 step 5): mpv's software render API into memory Skia wraps without a
 * copy, drawn by Compose, so the chrome draws over the video on every platform.
 *
 * - [MpvRenderer] owns the render context (`MPV_RENDER_API_TYPE_SW`) on its own thread. mpv's update callback (on one of mpv's
 *   threads) only wakes that thread; the thread asks `mpv_render_context_update` and, for a new frame or a new size, renders
 *   with `mpv_render_context_render` into the back buffer and publishes it.
 * - [FrameExchange] holds three buffers of the current size: the one the screen holds, the newest complete one, and the one
 *   being written, so the renderer never writes a buffer while it is drawn and a complete frame is always ready (mpv's
 *   software render blocks until each frame's target time, so the buffer being written is busy for most of a frame).
 * - [FrameBuffer] is one direct buffer of `stride x height` bytes (the stride is `width x 4` rounded up to 64, the address
 *   aligned to 64, as `render.h` asks for its fast path), allocated once per size, with its render parameters and the Skia
 *   [Pixmap] that wraps the same memory.
 * - [FrameCanvas] draws the newest frame: one `Image` per published frame from that pixmap (no pixel copy), letterboxed (FIT) or
 *   cropped (FILL); it tells the exchange its pixel size, and mpv renders at that size capped at the video's own.
 *
 * Pixels are mpv's `rgb0` (red, green, blue, then a byte mpv leaves undefined), which is exactly Skia's `RGB_888X` (whose fourth
 * byte Skia ignores), drawn as opaque.
 */
internal class MpvRenderer(
    private val mpv: MpvLibrary,
    private val handle: Pointer,
    private val exchange: FrameExchange,
) : AutoCloseable {

    @Volatile private var running = true
    @Volatile private var pending = true
    @Volatile private var created = false
    private val ready = CountDownLatch(1)
    private val thread = Thread(::renderLoop, "cosmotune-mpv-render").apply { isDaemon = true }

    /** mpv's update callback: wakes the render thread and nothing else (it must not call into mpv). */
    private val update = object : MpvLibrary.RenderUpdate {
        override fun invoke(cbCtx: Pointer?) = wake()
    }

    /** Starts the render thread and waits until its render context exists; false when mpv refused to create one. */
    fun start(): Boolean {
        exchange.onChange = ::wake
        thread.start()
        ready.await(START_TIMEOUT_S, TimeUnit.SECONDS)
        return created
    }

    /** Wakes the render thread: a new frame, a new target size or the video's size changed. */
    fun wake() {
        pending = true
        LockSupport.unpark(thread)
    }

    /** Frees the render context (on its thread) and stops it; call before the core is destroyed. */
    override fun close() {
        if (!running) return
        running = false
        exchange.onChange = {}
        wake()
        thread.join(STOP_TIMEOUT_MS)
    }

    private fun renderLoop() {
        val context = createContext()
        if (context == null) {
            ready.countDown()
            return
        }
        // The callback thread stays attached to the JVM between frames: no thread object per callback.
        Native.setCallbackThreadInitializer(update, CallbackThreadInitializer(true, false, "cosmotune-mpv-update"))
        mpv.mpv_render_context_set_update_callback(context, update, null)
        created = true
        ready.countDown()
        var renderedWidth = 0
        var renderedHeight = 0
        try {
            while (running) {
                while (!pending && running) LockSupport.park(this)
                pending = false
                if (!running) break
                val flags = mpv.mpv_render_context_update(context)
                val target = exchange.target()
                val width = FrameGeometry.widthOf(target)
                val height = FrameGeometry.heightOf(target)
                if (width <= 0 || height <= 0) continue
                val resized = width != renderedWidth || height != renderedHeight
                if (flags and MPV_RENDER_UPDATE_FRAME == 0L && !resized) continue
                try {
                    val buffer = exchange.beginWrite(width, height)
                    val result = mpv.mpv_render_context_render(context, buffer.params)
                    exchange.endWrite(buffer, published = result >= 0)
                    if (result >= 0 && flags and MPV_RENDER_UPDATE_FRAME != 0L) exchange.sourceFrame(buffer)
                    if (result >= 0) {
                        renderedWidth = width
                        renderedHeight = height
                    }
                } catch (e: Throwable) {
                    System.err.println("Cosmotune video: rendering a frame failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (e is Error) break // out of memory or a missing native: stop drawing, the sound plays on
                }
            }
        } finally {
            mpv.mpv_render_context_set_update_callback(context, null, null)
            mpv.mpv_render_context_free(context)
        }
    }

    private fun createContext(): Pointer? {
        val apiType = Memory(MPV_RENDER_API_TYPE_SW.length + 1L).apply { setString(0, MPV_RENDER_API_TYPE_SW) }
        val params = Memory(2 * MpvEventLayout.RENDER_PARAM_SIZE).apply {
            clear()
            setInt(0, MPV_RENDER_PARAM_API_TYPE)
            setPointer(MpvEventLayout.RENDER_PARAM_DATA, apiType)
            setInt(MpvEventLayout.RENDER_PARAM_SIZE, MPV_RENDER_PARAM_INVALID)
        }
        val result = PointerByReference()
        val code = mpv.mpv_render_context_create(result, handle, params)
        if (code < 0) {
            System.err.println("Cosmotune video: mpv_render_context_create: ${mpv.mpv_error_string(code)}")
            return null
        }
        return result.value
    }

    companion object {
        private const val START_TIMEOUT_S = 5L
        private const val STOP_TIMEOUT_MS = 2000L
    }
}

/**
 * One frame buffer of [width] x [height] pixels in mpv's `rgb0`: direct memory of [stride] x [height] bytes aligned to 64, the
 * `mpv_render_param` array that renders into it (size, format, stride, pointer, end), and the Skia [pixmap] over the same
 * memory. Made once per size; [serial] counts the frames published from it.
 */
internal class FrameBuffer(val width: Int, val height: Int) {
    val stride: Int = FrameGeometry.strideOf(width)
    private val memory: ByteBuffer = ByteBuffer.allocateDirect(stride * height + FrameGeometry.ALIGNMENT).alignedSlice(FrameGeometry.ALIGNMENT)
    val address: Long = Pointer.nativeValue(Native.getDirectBufferPointer(memory))

    private val size = Memory(8).apply {
        setInt(0, width)
        setInt(4, height)
    }
    private val format = Memory(FrameGeometry.MPV_FORMAT.length + 1L).apply { setString(0, FrameGeometry.MPV_FORMAT) }
    private val strideValue = Memory(Native.SIZE_T_SIZE.toLong()).apply {
        if (Native.SIZE_T_SIZE == 8) setLong(0, stride.toLong()) else setInt(0, stride)
    }
    private val block = Memory(4).apply { setInt(0, 1) }

    /**
     * `{SW_SIZE, SW_FORMAT, SW_STRIDE, SW_POINTER, BLOCK_FOR_TARGET_TIME, INVALID}` for `mpv_render_context_render`. Blocking until
     * the frame's target time (mpv's default, written out) keeps the picture in step with the sound; the third buffer of
     * [FrameExchange] is what makes the wait harmless.
     */
    val params: Memory = Memory(6 * MpvEventLayout.RENDER_PARAM_SIZE).apply {
        clear()
        val step = MpvEventLayout.RENDER_PARAM_SIZE
        val data = MpvEventLayout.RENDER_PARAM_DATA
        setInt(0, MPV_RENDER_PARAM_SW_SIZE)
        setPointer(data, size)
        setInt(step, MPV_RENDER_PARAM_SW_FORMAT)
        setPointer(step + data, format)
        setInt(2 * step, MPV_RENDER_PARAM_SW_STRIDE)
        setPointer(2 * step + data, strideValue)
        setInt(3 * step, MPV_RENDER_PARAM_SW_POINTER)
        setPointer(3 * step + data, Pointer(address))
        setInt(4 * step, MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME)
        setPointer(4 * step + data, block)
        setInt(5 * step, MPV_RENDER_PARAM_INVALID)
    }

    /** The same memory as a Skia pixmap: `RGB_888X`, opaque, [stride] bytes per row; made when the screen first draws it. */
    val pixmap: Pixmap by lazy {
        org.jetbrains.skia.impl.Library.staticLoad() // Skia's natives, loaded already wherever Compose draws
        Pixmap.make(ImageInfo(width, height, ColorType.RGB_888X, ColorAlphaType.OPAQUE), address, stride)
    }

    /** How many frames were published from this buffer; the screen makes a new image when it changes. */
    @Volatile var serial: Long = 0
        internal set

    /** Copies [other]'s pixels (a buffer of the same size) into this one; the frame smoothing keeps its own copies of the frames it blends. */
    fun copyFrom(other: FrameBuffer) {
        // The aligned slice of each buffer may be a few bytes longer than the picture, by a different amount: copy exactly the picture.
        val size = stride * height
        val from = other.memory.duplicate()
        from.clear()
        from.limit(size)
        val to = memory.duplicate()
        to.clear()
        to.limit(size)
        to.put(from)
    }

    /** The pixels, `stride x height` bytes, for reading (a view: the position and limit are the caller's own). */
    fun view(): ByteBuffer = memory.asReadOnlyBuffer()

    /** The pixel at ([x], [y]) as `0xRRGGBB` (tests and the look check read frames with it). */
    fun rgb(x: Int, y: Int): Int {
        val at = y * stride + x * 4
        return ((memory.get(at).toInt() and 0xff) shl 16) or ((memory.get(at + 1).toInt() and 0xff) shl 8) or (memory.get(at + 2).toInt() and 0xff)
    }
}

/**
 * The handover between the render thread and the screen: three [FrameBuffer]s of the current size, the one the screen [held],
 * the newest complete one ([published]), and the one being written, which is always a third: a buffer is never written while
 * it is drawn, and a complete frame is always there for the screen to take. (Two are not enough: mpv's software render
 * blocks until the frame's target time, `MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME`, so the buffer being written is busy for most
 * of each frame; with two, it would have to be the published one, and the screen would keep finding nothing new.) It also
 * gathers the pixel sizes the surfaces ask for and the video's size, which decide the render size ([target]).
 */
internal class FrameExchange {
    private class SizeRequest(var width: Int, var height: Int, var scale: VideoScale)

    private val lock = Any()
    private val slots = arrayOfNulls<FrameBuffer>(SLOTS)
    private var held = -1
    private var published = -1
    private var blank = false
    private var free = false
    private var epoch = 0
    private var writingEpoch = 0
    private val requests = HashMap<Any, SizeRequest>()
    private val sequence = MutableStateFlow(0L)

    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    /** Called when the target size may have changed (the renderer sets it to its wake-up). */
    @Volatile var onChange: () -> Unit = {}

    /** A counter that moves with every published frame or clear: the screen redraws when it changes. */
    val frames: StateFlow<Long> get() = sequence

    /** Which window the video is drawn in now, so smoothing reads that monitor's refresh rate. */
    val display = DisplayRateSource()

    /** Real-time frame smoothing, when the engine has it on; the renderer feeds it each new frame, the screen draws through it. */
    @Volatile var smoothing: SmoothingPipeline? = null

    /** The renderer made [buffer] from a new frame of the video (not a redraw at a new size): the smoothing takes its measure and copy. */
    fun sourceFrame(buffer: FrameBuffer) {
        val pipeline = smoothing ?: return
        try {
            pipeline.onSourceFrame(buffer, System.nanoTime())
        } catch (e: Throwable) {
            pipeline.fail(e) // never lets the frames stop: the plain picture goes on
        }
    }

    /** The video's own width in pixels (0 for none). */
    val videoWidthPx: Int get() = videoWidth

    /** The video's own height in pixels (0 for none). */
    val videoHeightPx: Int get() = videoHeight

    /** The video's own size (0 x 0 for none), the cap of the render size. */
    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        onChange()
    }

    /** A surface ([key]) is [width] x [height] pixels and places the picture by [scale]; cheap when nothing changed. */
    fun request(key: Any, width: Int, height: Int, scale: VideoScale) {
        val changed = synchronized(lock) {
            val current = requests[key]
            when {
                current == null -> {
                    requests[key] = SizeRequest(width, height, scale)
                    true
                }
                current.width != width || current.height != height || current.scale != scale -> {
                    current.width = width
                    current.height = height
                    current.scale = scale
                    true
                }
                else -> false
            }
        }
        if (changed) onChange()
    }

    /** The surface [key] left the screen. */
    fun forget(key: Any) {
        synchronized(lock) { requests.remove(key) }
        onChange()
    }

    /** The size to render at now, packed by [FrameGeometry.pack] (0 for none): the largest surface's, capped at the video's size. */
    fun target(): Long {
        var width = 0
        var height = 0
        var scale = VideoScale.FIT
        synchronized(lock) {
            for (request in requests.values) {
                if (request.width.toLong() * request.height > width.toLong() * height) {
                    width = request.width
                    height = request.height
                    scale = request.scale
                }
            }
        }
        return FrameGeometry.renderSize(width, height, videoWidth, videoHeight, scale)
    }

    /** The renderer's buffer for the next frame of [width] x [height]: neither the one the screen holds nor the published one. Render thread only. */
    fun beginWrite(width: Int, height: Int): FrameBuffer {
        var index = 0
        val existing: FrameBuffer?
        synchronized(lock) {
            while (index == held || index == published) index++
            existing = slots[index]
            writingEpoch = epoch
        }
        if (existing != null && existing.width == width && existing.height == height) return existing
        val fresh = FrameBuffer(width, height) // once per size
        synchronized(lock) { slots[index] = fresh }
        return fresh
    }

    /** The renderer finished [buffer]; [published] says whether it holds a frame to show. Render thread only. */
    fun endWrite(buffer: FrameBuffer, published: Boolean) {
        if (!published) return
        synchronized(lock) {
            if (writingEpoch != epoch) return // a frame of the video a clear sent away
            val index = slots.indexOfFirst { it === buffer }
            if (index < 0) return
            this.published = index // the previous published buffer, never taken, is free again
            buffer.serial = buffer.serial + 1
        }
        sequence.update { it + 1 }
    }

    /** The newest complete frame for the screen (which then holds it), or the one it holds, or null for nothing to show. */
    fun acquire(): FrameBuffer? = synchronized(lock) {
        when {
            published >= 0 -> {
                held = published
                published = -1
                blank = false
                free = false
                slots[held]
            }
            blank -> {
                if (free && held >= 0) slots[held] = null
                held = -1
                blank = false
                free = false
                null
            }
            held >= 0 -> slots[held]
            else -> null
        }
    }

    /**
     * Shows nothing until the next frame (a new load). With [freeBuffers] (an unload) the buffers are let go as well: the ones
     * the screen does not hold at once, the held one when the screen next looks.
     */
    fun clear(freeBuffers: Boolean = false) {
        synchronized(lock) {
            published = -1
            blank = true
            epoch++
            if (freeBuffers) {
                free = true
                for (i in slots.indices) if (i != held) slots[i] = null
            }
        }
        sequence.update { it + 1 }
    }

    /** How many buffers the exchange keeps (tests). */
    internal fun buffersKept(): Int = synchronized(lock) { slots.count { it != null } }

    companion object {
        /** Held by the screen, published, being written. */
        const val SLOTS = 3
    }
}

/** The pure arithmetic of the frames: render size, stride, and where the picture goes in its box. */
internal object FrameGeometry {
    /** Bytes the buffer's address and stride are aligned to (`render.h`: "a multiple of 64"). */
    const val ALIGNMENT = 64

    /** mpv's software-render pixel format: `rgb0`, which is Skia's `RGB_888X`. */
    const val MPV_FORMAT = "rgb0"

    /** `width x 4` rounded up to a multiple of [ALIGNMENT]. */
    fun strideOf(width: Int): Int = (width * 4 + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT

    fun pack(width: Int, height: Int): Long = (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
    fun widthOf(packed: Long): Int = (packed ushr 32).toInt()
    fun heightOf(packed: Long): Int = packed.toInt()

    /**
     * The size mpv renders at for a box of [boxWidth] x [boxHeight] pixels: the video's shape scaled to fit the box (FIT) or to
     * cover it (FILL), never larger than the video's own [videoWidth] x [videoHeight] (Skia scales up on the screen). 0 when
     * either is unknown.
     */
    fun renderSize(boxWidth: Int, boxHeight: Int, videoWidth: Int, videoHeight: Int, scale: VideoScale): Long {
        if (boxWidth <= 0 || boxHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return 0
        val sx = boxWidth.toDouble() / videoWidth
        val sy = boxHeight.toDouble() / videoHeight
        val s = min(1.0, if (scale == VideoScale.FIT) min(sx, sy) else max(sx, sy))
        return pack(max(1, (videoWidth * s).roundToInt()), max(1, (videoHeight * s).roundToInt()))
    }

    /**
     * Where an image of [imageWidth] x [imageHeight] goes in a box of [boxWidth] x [boxHeight]: into [out] as the source
     * rectangle (left, top, right, bottom in image pixels) then the destination (in box pixels). FIT shows the whole image
     * centred with bars on two sides; FILL covers the box and crops the image's centre.
     */
    fun placement(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int, scale: VideoScale, out: FloatArray) {
        val imageAspect = imageWidth.toFloat() / imageHeight
        val boxAspect = boxWidth / boxHeight
        if (scale == VideoScale.FIT) {
            out[0] = 0f; out[1] = 0f; out[2] = imageWidth.toFloat(); out[3] = imageHeight.toFloat()
            val width = if (imageAspect > boxAspect) boxWidth else boxHeight * imageAspect
            val height = if (imageAspect > boxAspect) boxWidth / imageAspect else boxHeight
            out[4] = (boxWidth - width) / 2f; out[5] = (boxHeight - height) / 2f
            out[6] = out[4] + width; out[7] = out[5] + height
        } else {
            val width = if (imageAspect > boxAspect) imageHeight * boxAspect else imageWidth.toFloat()
            val height = if (imageAspect > boxAspect) imageHeight.toFloat() else imageWidth / boxAspect
            out[0] = (imageWidth - width) / 2f; out[1] = (imageHeight - height) / 2f
            out[2] = out[0] + width; out[3] = out[1] + height
            out[4] = 0f; out[5] = 0f; out[6] = boxWidth; out[7] = boxHeight
        }
    }
}

/**
 * Draws the newest frame of [exchange] into the space [modifier] gives it, placed by [scale], and reports that space's pixel
 * size to the exchange. The frame counter is read only in the draw lambda, so a new frame redraws without recomposing.
 */
@Composable
internal fun FrameCanvas(exchange: FrameExchange, modifier: Modifier, scale: VideoScale, aspect: VideoAspect? = null) {
    val painter = remember(exchange) { FramePainter(exchange) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(exchange) { exchange.frames.collect { tick.longValue = it } }
    val pace = remember { mutableLongStateOf(0L) }
    SmoothingClock(exchange.smoothing, exchange.frames, pace)
    DisposableEffect(painter) { onDispose { painter.dispose() } }
    // The window this surface is in tells smoothing which monitor's refresh rate to use.
    val host = LocalVideoHostWindow.current
    DisposableEffect(painter, host) {
        exchange.display.attach(painter, host)
        onDispose { exchange.display.detach(painter) }
    }
    Canvas(modifier) {
        tick.longValue // a new frame invalidates this draw only
        pace.longValue // so does the smoothing clock, while in-between frames are being made
        painter.draw(this, scale, aspect)
    }
}

/**
 * The screen side of the exchange for one surface: the frame it holds, the `Image` made from it (one per published frame; the
 * previous one is closed), and the placement rectangles, recomputed only when a size changes.
 */
internal class FramePainter(private val exchange: FrameExchange) {
    private var frame: FrameBuffer? = null
    private var serial = -1L
    private var image: Image? = null
    private val placement = FloatArray(8)
    private var placedFor = LongArray(4)
    private var src: Rect? = null
    private var dst: Rect? = null
    private val paint = Paint()

    fun draw(scope: DrawScope, scale: VideoScale, aspect: VideoAspect? = null) {
        val boxWidth = scope.size.width
        val boxHeight = scope.size.height
        exchange.request(this, boxWidth.roundToInt(), boxHeight.roundToInt(), scale)
        val next = exchange.acquire()
        if (next == null) {
            release()
            return
        }
        if (next !== frame || next.serial != serial) {
            image?.close()
            image = Image.makeFromPixmap(next.pixmap)
            frame = next
            serial = next.serial
        }
        val box = FrameGeometry.pack(boxWidth.roundToInt(), boxHeight.roundToInt())
        val picture = FrameGeometry.pack(next.width, next.height)
        // A local file's aspect choices other than fit and fill: the whole picture drawn at the size AspectMath gives, centred.
        val shaped = aspect != null && aspect != VideoAspect.FIT && aspect != VideoAspect.FILL
        val shapeKey = if (shaped) aspect!!.ordinal.toLong() + 1 else 0L
        val video = FrameGeometry.pack(exchange.videoWidthPx, exchange.videoHeightPx)
        if (src == null || placedFor[0] != box || placedFor[1] != picture || placedFor[2] != scale.ordinal.toLong() || placedFor[3] != shapeKey + (video shl 8)) {
            if (shaped) {
                val drawn = AspectMath.drawnSize(aspect!!, boxWidth, boxHeight, exchange.videoWidthPx.toFloat(), exchange.videoHeightPx.toFloat())
                val left = (boxWidth - drawn.width) / 2f
                val top = (boxHeight - drawn.height) / 2f
                src = Rect(0f, 0f, next.width.toFloat(), next.height.toFloat())
                dst = Rect(left, top, left + drawn.width, top + drawn.height)
            } else {
                FrameGeometry.placement(boxWidth, boxHeight, next.width, next.height, scale, placement)
                src = Rect(placement[0], placement[1], placement[2], placement[3])
                dst = Rect(placement[4], placement[5], placement[6], placement[7])
            }
            placedFor[0] = box
            placedFor[1] = picture
            placedFor[2] = scale.ordinal.toLong()
            placedFor[3] = shapeKey + (video shl 8)
        }
        val canvas = scope.drawContext.canvas.nativeCanvas
        val smoothing = exchange.smoothing
        if (smoothing != null && smoothing.draw(canvas, boxWidth, boxHeight, src!!, dst!!, next.width, next.height, System.nanoTime())) return
        canvas.drawImageRect(image!!, src!!, dst!!, SamplingMode.LINEAR, paint, true)
    }

    private fun release() {
        image?.close()
        image = null
        frame = null
        serial = -1
    }

    fun dispose() {
        exchange.forget(this)
        release()
    }
}
