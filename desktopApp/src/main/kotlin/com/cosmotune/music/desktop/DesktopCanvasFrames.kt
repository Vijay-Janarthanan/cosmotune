package com.cosmotune.music.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A frame's size in pixels. */
internal data class CanvasFrameSize(val width: Int, val height: Int)

/**
 * The size a video is decoded at: the size it is drawn at on screen (cropped to fill [shownWidth] x [shownHeight], as the
 * canvas draws it), never larger than the source and never longer than [maxEdge] on its long side. With no shown size yet
 * it is the source, capped at [maxEdge]. Both sides are even, which the scaler wants for 4:2:0 sources.
 */
internal fun canvasOutputSize(sourceWidth: Int, sourceHeight: Int, shownWidth: Int, shownHeight: Int, maxEdge: Int): CanvasFrameSize {
    if (sourceWidth <= 0 || sourceHeight <= 0) return CanvasFrameSize(2, 2)
    var scale = min(1.0, maxEdge.toDouble() / max(sourceWidth, sourceHeight))
    if (shownWidth > 0 && shownHeight > 0) {
        // The canvas crops the video to fill its box, so the side that overflows sets the scale.
        scale = min(scale, max(shownWidth.toDouble() / sourceWidth, shownHeight.toDouble() / sourceHeight))
    }
    fun side(source: Int): Int {
        val largest = (source / 2) * 2
        val wanted = ceil(source * scale - 1e-6).toInt()
        return (((wanted + 1) / 2) * 2).coerceIn(2, largest.coerceAtLeast(2))
    }
    return CanvasFrameSize(side(sourceWidth), side(sourceHeight))
}

/** Whether a video should be decoded now: only while someone can see it. */
internal object CanvasVideoGate {
    /**
     * [windowShown]: the window holding the video is on screen (not minimised, not hidden in the tray). [onScreen]: the video's
     * own box is inside the window. [hasFrame] and [presentationAlpha]: a video faded all the way out by its caller is not seen,
     * but before its first frame it is decoded regardless, since callers fade it in only once that frame is there.
     */
    fun shouldDecode(windowShown: Boolean, onScreen: Boolean, hasFrame: Boolean, presentationAlpha: Float): Boolean =
        windowShown && onScreen && (!hasFrame || presentationAlpha > 0f)
}

/** Where the decoder writes one frame: a bitmap's own pixels, laid out for sws_scale. */
internal class CanvasFrameTarget(val width: Int, val height: Int, address: Long, val rowBytes: Int) {
    private val pixels = RawBytes(address, rowBytes.toLong() * height)
    val planes = PointerPointer<BytePointer>(4L).put(0L, pixels)
    val strides = IntPointer(4L).put(0L, rowBytes)
    val address: Long = address

    /** Copies the frame out as tightly packed BGRA, for the few callers that want bytes. */
    fun copyTo(into: ByteArray): Boolean {
        if (rowBytes != width * 4 || into.size < width * height * 4) return false
        pixels.position(0L).get(into, 0, width * height * 4)
        return true
    }

    fun free() {
        planes.deallocate()
        strides.deallocate()
    }
}

/** Memory JavaCPP did not allocate and must never free: here, a Skia bitmap's pixels. */
private class RawBytes(address: Long, size: Long) : BytePointer() {
    init {
        this.address = address
        this.limit = size
        this.capacity = size
    }
}

/**
 * The video's frames, without a new picture per frame: two bitmaps of the decoded size, written in turn. The decoder fills the
 * one not on screen ([write]) while the other is drawn; [present] then swaps them. A bitmap is made only when the decoded size
 * changes. Compose draws an immutable bitmap without copying it, and [present] tells Skia the pixels changed, so the frame the
 * GPU uploads is the new one.
 *
 * [ensure], [present], [hidden] and [close] run on the UI thread; [write] runs its block on the decoder's thread. A bitmap is
 * freed only once no write is in flight, so a decode that outlives its screen never writes into freed memory.
 */
internal class CanvasFrameRing {
    private class Slot(val bitmap: Bitmap, val image: ImageBitmap, val target: CanvasFrameTarget) {
        fun free() {
            target.free()
            bitmap.close()
        }
    }

    private val lock = Any()
    private var slots: List<Slot> = emptyList()
    private val retired = ArrayList<Slot>()
    private var front = -1
    private var writing = false
    private var closed = false

    /** Bitmaps made so far; two per decoded size. */
    var allocations = 0
        private set

    val width: Int get() = synchronized(lock) { slots.firstOrNull()?.target?.width ?: 0 }
    val height: Int get() = synchronized(lock) { slots.firstOrNull()?.target?.height ?: 0 }

    /** Makes the bitmaps [width] x [height], when they are not that already. False when there is no memory for them. */
    fun ensure(width: Int, height: Int): Boolean = synchronized(lock) {
        if (closed) return false
        slots.firstOrNull()?.target?.let { if (it.width == width && it.height == height) return true }
        // The bitmap on screen stays until the next frame replaces it.
        retired += slots
        slots = emptyList()
        front = -1
        val made = ArrayList<Slot>(2)
        repeat(2) { allocate(width, height)?.let(made::add) }
        if (made.size < 2) {
            made.forEach { it.free() }
            return false
        }
        slots = made
        true
    }

    private fun allocate(width: Int, height: Int): Slot? {
        val bitmap = Bitmap()
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
        if (!bitmap.allocPixels(info)) {
            bitmap.close()
            return null
        }
        val pixmap = bitmap.peekPixels()
        val address = pixmap?.addr ?: 0L
        pixmap?.close()
        if (address == 0L) {
            bitmap.close()
            return null
        }
        bitmap.erase(0xFF000000.toInt())
        // Immutable: Compose's draw then shares these pixels rather than copying them every time it paints.
        bitmap.setImmutable()
        allocations++
        return Slot(bitmap, bitmap.asComposeImageBitmap(), CanvasFrameTarget(width, height, address, bitmap.rowBytes))
    }

    /** Runs [block] on the bitmap not on screen; null when there is none (closed, or not made yet). */
    fun <T> write(block: (CanvasFrameTarget) -> T): T? {
        val target = synchronized(lock) {
            if (closed || slots.size < 2 || writing) return null
            writing = true
            slots[backIndex()].target
        }
        try {
            return block(target)
        } finally {
            synchronized(lock) {
                writing = false
                if (closed) freeAll() else freeRetired()
            }
        }
    }

    private fun backIndex(): Int = if (front == 0) 1 else 0

    /** The bitmap just written, now the one on screen. */
    fun present(): ImageBitmap? = synchronized(lock) {
        if (closed || slots.size < 2) return null
        val shown = backIndex()
        slots[shown].bitmap.notifyPixelsChanged()
        front = shown
        freeRetired()
        slots[shown].image
    }

    /** Nothing is on screen any more (the caller has stopped drawing the last frame). */
    fun hidden() = synchronized(lock) {
        front = -1
        freeRetired()
    }

    fun close() = synchronized(lock) {
        closed = true
        if (!writing) freeAll()
    }

    private fun freeRetired() {
        if (writing) return
        retired.forEach { it.free() }
        retired.clear()
    }

    private fun freeAll() {
        retired += slots
        slots = emptyList()
        front = -1
        freeRetired()
    }
}

/**
 * A small copy of [frame] for whoever keeps a frame to tint or blur from: the bitmaps on screen are rewritten with every frame,
 * so they are never handed out themselves. Null when there is no memory for the copy.
 */
internal fun canvasFrameSnapshot(frame: ImageBitmap, maxEdge: Int): ImageBitmap? = runCatching {
    val scale = min(1f, maxEdge.coerceAtLeast(1).toFloat() / max(frame.width, frame.height))
    val width = (frame.width * scale).toInt().coerceAtLeast(1)
    val height = (frame.height * scale).toInt().coerceAtLeast(1)
    val copy = Bitmap()
    if (!copy.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))) {
        copy.close()
        return null
    }
    val scaled = Image.makeFromBitmap(frame.asSkiaBitmap()).use { image ->
        val pixmap = copy.peekPixels() ?: return@use false
        try { image.scalePixels(pixmap, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), false) } finally { pixmap.close() }
    }
    if (!scaled) {
        copy.close()
        return null
    }
    copy.setImmutable()
    copy.asComposeImageBitmap()
}.getOrNull()
