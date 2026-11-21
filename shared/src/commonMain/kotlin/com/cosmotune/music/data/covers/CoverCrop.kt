package com.cosmotune.music.data.covers

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a photo sits in the cover circle: [zoom] (1 is the photo's short side just filling the circle) and how far its middle is
 * moved from the circle's middle ([offsetX], [offsetY], in the circle's own pixels).
 */
data class CoverPlacement(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

/** The square of the photo that becomes the cover, in the photo's pixels. */
data class CropSquare(val left: Int, val top: Int, val side: Int)

/**
 * The maths of placing a photo in the circle with drag and pinch. The circle is a square of `viewport` pixels on screen; the photo
 * always covers all of it, so the saved cover never has a gap. Pure, so it is tested on the JVM.
 */
object CoverCrop {
    const val MAX_ZOOM = 5f

    /** Screen pixels per photo pixel at zoom 1: the short side fills the circle. */
    private fun base(width: Int, height: Int, viewport: Float): Float = viewport / min(width, height).coerceAtLeast(1)

    /** [placement] with the zoom kept in 1..[MAX_ZOOM] and the photo moved back so it covers the whole circle. */
    fun clamp(placement: CoverPlacement, width: Int, height: Int, viewport: Float): CoverPlacement {
        val zoom = placement.zoom.coerceIn(1f, MAX_ZOOM)
        val scale = base(width, height, viewport) * zoom
        val spareX = ((width * scale - viewport) / 2f).coerceAtLeast(0f)
        val spareY = ((height * scale - viewport) / 2f).coerceAtLeast(0f)
        // + 0f: a -0 offset reads as the 0 it is.
        return CoverPlacement(zoom, placement.offsetX.coerceIn(-spareX, spareX) + 0f, placement.offsetY.coerceIn(-spareY, spareY) + 0f)
    }

    /**
     * One step of a drag or pinch: the zoom changes by [zoomBy] about the fingers' middle ([centroidX], [centroidY], from the circle's
     * top-left corner), so the part of the photo under them stays under them, then the photo moves by the pan.
     */
    fun gesture(
        placement: CoverPlacement, width: Int, height: Int, viewport: Float,
        panX: Float, panY: Float, zoomBy: Float, centroidX: Float, centroidY: Float,
    ): CoverPlacement {
        val by = if (zoomBy.isFinite() && zoomBy > 0f) zoomBy else 1f
        val zoom = (placement.zoom * by).coerceIn(1f, MAX_ZOOM)
        val change = zoom / placement.zoom
        val middle = viewport / 2f
        val centreX = centroidX + (middle + placement.offsetX - centroidX) * change + panX
        val centreY = centroidY + (middle + placement.offsetY - centroidY) * change + panY
        return clamp(CoverPlacement(zoom, centreX - middle, centreY - middle), width, height, viewport)
    }

    /** The same placement for a circle of another size (the screen turned, or the window resized). */
    fun rescale(placement: CoverPlacement, from: Float, to: Float): CoverPlacement {
        if (from <= 0f) return placement
        val k = to / from
        return placement.copy(offsetX = placement.offsetX * k, offsetY = placement.offsetY * k)
    }

    /** The square of the photo inside the circle, kept inside the photo. */
    fun square(placement: CoverPlacement, width: Int, height: Int, viewport: Float): CropSquare {
        val p = clamp(placement, width, height, viewport)
        val scale = base(width, height, viewport) * p.zoom
        val side = (viewport / scale).roundToInt().coerceIn(1, min(width, height).coerceAtLeast(1))
        val left = ((width * scale / 2f - viewport / 2f - p.offsetX) / scale).roundToInt().coerceIn(0, (width - side).coerceAtLeast(0))
        val top = ((height * scale / 2f - viewport / 2f - p.offsetY) / scale).roundToInt().coerceIn(0, (height - side).coerceAtLeast(0))
        return CropSquare(left, top, side)
    }
}

/** Cutting the placed square out of a photo's pixels (ARGB, row by row) at the size that is kept. Pure, so it is tested on the JVM. */
object CoverPixels {
    /** What is kept on the device: about 1200 px. */
    const val DEVICE_SIDE = 1200

    /** What goes to the account: about 400 px. */
    const val ACCOUNT_SIDE = 400

    /** [target], or less when the photo's square is smaller: a cover is never blown up. */
    fun outputSide(square: CropSquare, target: Int): Int = square.side.coerceIn(1, target)

    /**
     * The [square] of [argb] (a [width] x [height] picture) as an [out] x [out] picture: each pixel the average of the photo's pixels it
     * covers, see-through parts laid on white (a JPEG has no transparency).
     */
    fun crop(argb: IntArray, width: Int, height: Int, square: CropSquare, out: Int): IntArray {
        val result = IntArray(out * out)
        val side = square.side.coerceAtLeast(1)
        for (y in 0 until out) {
            val y0 = (square.top + y * side / out).coerceIn(0, height - 1)
            val y1 = (square.top + (y + 1) * side / out).coerceIn(y0 + 1, height)
            for (x in 0 until out) {
                val x0 = (square.left + x * side / out).coerceIn(0, width - 1)
                val x1 = (square.left + (x + 1) * side / out).coerceIn(x0 + 1, width)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (sy in y0 until y1) {
                    val row = sy * width
                    for (sx in x0 until x1) {
                        val c = argb[row + sx]
                        val a = (c ushr 24) and 0xFF
                        // On white: the colour where it is opaque, white where it is clear.
                        r += (((c shr 16) and 0xFF) * a + 255 * (255 - a)) / 255
                        g += (((c shr 8) and 0xFF) * a + 255 * (255 - a)) / 255
                        b += ((c and 0xFF) * a + 255 * (255 - a)) / 255
                        n++
                    }
                }
                val half = n / 2
                result[y * out + x] = (0xFF shl 24) or
                    (((r + half) / n).toInt() shl 16) or (((g + half) / n).toInt() shl 8) or ((b + half) / n).toInt()
            }
        }
        return result
    }
}

/** Fitting the account's copy of a cover under its size limit. */
object CoverJpeg {
    /** The JPEG qualities tried, best first. */
    val QUALITIES = listOf(82, 74, 66, 58, 50)

    /** The first of [QUALITIES] whose picture is at most [maxBytes], or null when none is. */
    fun fitWithin(maxBytes: Int, encode: (quality: Int) -> ByteArray): ByteArray? {
        for (quality in QUALITIES) {
            val bytes = encode(quality)
            if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
        }
        return null
    }

    /** Whether [bytes] start the way every JPEG does. */
    fun looksLikeJpeg(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
}
