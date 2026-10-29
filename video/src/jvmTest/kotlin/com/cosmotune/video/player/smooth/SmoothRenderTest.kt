package com.cosmotune.video.player.smooth

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws a synthetic square that moves 12 pixels between two frames at phase 0.5 through the Blend and Smooth paths onto an
 * offscreen surface, and reads the pixels back: a blend shows both squares half-faded, the motion-compensated frame shows one
 * square halfway between them.
 */
class SmoothRenderTest {
    private val width = 640
    private val height = 360
    private val top = 140
    private val size = 64
    private val before = 100 // the square's left edge in the older frame
    private val after = 112 // and in the newer one: it moved 12 pixels
    private val mid = (before + after) / 2

    private fun noise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }

    /** Smooth value noise, so that a block of the picture has a texture to match. */
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

    /** A frame as mpv writes it (`rgb0`): a dark textured background that stays, and a bright textured square at [left] that moves. */
    private fun frame(left: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val inside = x >= left && x < left + size && y >= top && y < top + size
            val level = if (inside) 190f + 40f * texture((x - left).toFloat(), (y - top).toFloat(), 2) else 40f + 30f * texture(x.toFloat(), y.toFloat(), 1)
            val v = level.toInt().coerceIn(0, 255).toByte()
            val at = (y * width + x) * 4
            bytes[at] = v; bytes[at + 1] = v; bytes[at + 2] = v; bytes[at + 3] = 0
        }
        return bytes
    }

    private fun image(bytes: ByteArray): Image =
        Image.makeRaster(ImageInfo(width, height, ColorType.RGB_888X, ColorAlphaType.OPAQUE), bytes, width * 4)

    private fun luma(bytes: ByteArray, extractor: LumaExtractor): LumaPlane =
        extractor.newPlane().also { extractor.extract(ByteBuffer.wrap(bytes), width * 4, it) }

    /** The brightness (red channel) of every pixel of row [y] after [draw] drew onto a fresh surface. */
    private fun row(y: Int, draw: (Canvas) -> Unit): IntArray {
        org.jetbrains.skia.impl.Library.staticLoad()
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(0xff000000.toInt())
        draw(surface.canvas)
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        assertTrue(surface.readPixels(bitmap, 0, 0))
        return IntArray(width) { x -> (bitmap.getColor(x, y) shr 16) and 0xff }
    }

    private val dest get() = Rect.makeWH(width.toFloat(), height.toFloat())
    private val middleRow get() = top + size / 2

    /** The centre of the bright mass of a row, above the background level. */
    private fun centroid(pixels: IntArray): Double {
        var sum = 0.0
        var weight = 0.0
        for (x in 40 until 300) {
            val w = (pixels[x] - 70).coerceAtLeast(0).toDouble()
            sum += w * x
            weight += w
        }
        return sum / weight
    }

    @Test
    fun blendAtTheMiddleShowsBothSquaresHalfFaded() {
        val older = image(frame(before))
        val newer = image(frame(after))
        val pixels = row(middleRow) { SmoothRenderer().use { r -> r.drawBlend(it, older, newer, dest, SmoothPolicy.blendWeight(0.5f, 100)) } }
        // only the older square (left strip) and only the newer square (right strip) are about half way between square and background
        assertTrue(pixels[before + 3] in 100..170, "left strip ${pixels[before + 3]}")
        assertTrue(pixels[after + size - 3] in 100..170, "right strip ${pixels[after + size - 3]}")
        assertTrue(pixels[mid + size / 2] > 185, "where the squares overlap it is bright: ${pixels[mid + size / 2]}")
        val centre = centroid(pixels)
        assertTrue(centre > before + size / 2 && centre < after + size / 2, "centre of mass $centre is between the two squares")
    }

    @Test
    fun smoothAtTheMiddleShowsOneSquareHalfWayBetween() {
        val olderBytes = frame(before)
        val newerBytes = frame(after)
        val extractor = LumaExtractor(width, height)
        val field = MotionField(extractor.width / MotionEstimator.BLOCK, extractor.height / MotionEstimator.BLOCK)
        MotionEstimator().estimate(luma(olderBytes, extractor).data, luma(newerBytes, extractor).data, extractor.width, extractor.height, MotionParams.Default, field)
        assertTrue(!field.sceneCut, "a square moving 12 pixels is not a cut")
        val older = image(olderBytes)
        val newer = image(newerBytes)
        val fieldImage = MotionFieldCodec(field.columns, field.rows, 13f).toImage(field)
        val pixels = row(middleRow) {
            SmoothRenderer().use { r -> r.drawWarp(it, older, newer, fieldImage, dest, 0.5f, 13f, extractor.width, extractor.height, SmoothPolicy.mismatchBias(50)) }
        }
        // The square is now at 106 .. 170: background just outside, bright just inside, where a blend would show half-faded strips.
        assertTrue(pixels[mid - 6] < 110, "left of the new square, ${pixels[mid - 6]}")
        assertTrue(pixels[mid + 6] > 170, "inside its left edge, ${pixels[mid + 6]}")
        assertTrue(pixels[mid + size - 6] > 170, "inside its right edge, ${pixels[mid + size - 6]}")
        assertTrue(pixels[mid + size + 6] < 110, "right of the new square, ${pixels[mid + size + 6]}")
        val centre = centroid(pixels)
        assertTrue(centre > before + size / 2 && centre < after + size / 2, "centre of mass $centre is between the two squares")
        assertTrue(kotlin.math.abs(centre - (mid + size / 2.0)) < 3.0, "and about half way: $centre against ${mid + size / 2.0}")
    }

    @Test
    fun phaseZeroAndOneAreTheSourceFramesThemselves() {
        val olderBytes = frame(before)
        val newerBytes = frame(after)
        val extractor = LumaExtractor(width, height)
        val field = MotionField(extractor.width / MotionEstimator.BLOCK, extractor.height / MotionEstimator.BLOCK)
        MotionEstimator().estimate(luma(olderBytes, extractor).data, luma(newerBytes, extractor).data, extractor.width, extractor.height, MotionParams.Default, field)
        val older = image(olderBytes)
        val newer = image(newerBytes)
        val fieldImage = MotionFieldCodec(field.columns, field.rows, 13f).toImage(field)
        val atZero = row(middleRow) {
            SmoothRenderer().use { r -> r.drawWarp(it, older, newer, fieldImage, dest, 0f, 13f, extractor.width, extractor.height, SmoothPolicy.mismatchBias(50)) }
        }
        val atOne = row(middleRow) {
            SmoothRenderer().use { r -> r.drawWarp(it, older, newer, fieldImage, dest, 1f, 13f, extractor.width, extractor.height, SmoothPolicy.mismatchBias(50)) }
        }
        val first = row(middleRow) { SmoothRenderer().use { r -> r.drawSingle(it, older, dest) } }
        val second = row(middleRow) { SmoothRenderer().use { r -> r.drawSingle(it, newer, dest) } }
        val farFromEdgesZero = (30 until 300).count { kotlin.math.abs(atZero[it] - first[it]) <= 12 }
        val farFromEdgesOne = (30 until 300).count { kotlin.math.abs(atOne[it] - second[it]) <= 12 }
        assertTrue(farFromEdgesZero >= 260, "phase 0 matches the older frame at $farFromEdgesZero of 270 pixels")
        assertTrue(farFromEdgesOne >= 260, "phase 1 matches the newer frame at $farFromEdgesOne of 270 pixels")
    }

    private inline fun <R> SmoothRenderer.use(block: (SmoothRenderer) -> R): R = try { block(this) } finally { close() }
}
