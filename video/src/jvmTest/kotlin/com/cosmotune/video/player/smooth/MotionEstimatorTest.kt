package com.cosmotune.video.player.smooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Block matching finds the move that was put into a synthetic picture, and tells a cut from a move. */
class MotionEstimatorTest {
    private val width = 320
    private val height = 176
    private val estimator = MotionEstimator()

    /** A smooth, deterministic texture over the whole plane: value noise on a grid of 4 pixels, seeded. */
    private class Texture(private val seed: Int) {
        private fun lattice(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263 + seed * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xffff) / 65535f
        }

        fun at(x: Float, y: Float): Float {
            val gx = x / 4f
            val gy = y / 4f
            val x0 = Math.floor(gx.toDouble()).toInt()
            val y0 = Math.floor(gy.toDouble()).toInt()
            val fx = gx - x0
            val fy = gy - y0
            val top = lattice(x0, y0) * (1 - fx) + lattice(x0 + 1, y0) * fx
            val bottom = lattice(x0, y0 + 1) * (1 - fx) + lattice(x0 + 1, y0 + 1) * fx
            return top * (1 - fy) + bottom * fy
        }
    }

    /** The plane showing [texture] shifted so that its content has moved by ([moveX], [moveY]) from the unshifted one. */
    private fun plane(texture: Texture, moveX: Float = 0f, moveY: Float = 0f): LumaPlane {
        val plane = LumaPlane(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            plane.data[y * width + x] = (texture.at(x - moveX + 1000f, y - moveY + 1000f) * 255f).toInt().toByte()
        }
        return plane
    }

    private fun field() = MotionField(width / MotionEstimator.BLOCK, height / MotionEstimator.BLOCK)

    /** Blocks away from the border, where the content that moved in is not in the older picture. */
    private fun interior(f: MotionField, margin: Int = 2): List<Int> =
        (margin until f.rows - margin).flatMap { r -> (margin until f.columns - margin).map { c -> r * f.columns + c } }

    @Test
    fun aKnownShiftComesBackAsThatVector() {
        val texture = Texture(7)
        for ((dx, dy) in listOf(3 to -2, -5 to 4, 0 to 0, 1 to 0, 0 to -3, 6 to 6)) {
            val older = plane(texture)
            val newer = plane(texture, dx.toFloat(), dy.toFloat())
            val f = field()
            estimator.estimate(older.data, newer.data, width, height, MotionParams.Default, f)
            val blocks = interior(f)
            val right = blocks.count { abs(f.vx[it] - dx) <= 0.35f && abs(f.vy[it] - dy) <= 0.35f }
            assertTrue(right >= blocks.size * 0.97, "shift ($dx, $dy): only $right of ${blocks.size} blocks agree")
            assertFalse(f.sceneCut, "a shift is not a cut")
            assertTrue(blocks.all { f.confidence[it] > 0.9f }, "a perfect match is trusted")
        }
    }

    @Test
    fun aHalfPixelShiftIsFoundBetweenItsNeighbours() {
        val texture = Texture(11)
        val f = field()
        estimator.estimate(plane(texture).data, plane(texture, 2.5f, 0f).data, width, height, MotionParams.Default, f)
        val blocks = interior(f)
        val between = blocks.count { f.vx[it] in 2.0f..3.0f }
        assertTrue(between >= blocks.size * 0.9, "only $between of ${blocks.size} blocks between 2 and 3")
    }

    @Test
    fun theLargestMoveInRangeIsFoundAndOneBeyondItIsNot() {
        val texture = Texture(3)
        val f = field()
        val wide = MotionParams(range = 12, tolerance = 14f, cutFraction = 0.6f, cutMad = 28f)
        estimator.estimate(plane(texture).data, plane(texture, 11f, -9f).data, width, height, wide, f)
        val blocks = interior(f, margin = 3)
        assertTrue(blocks.count { abs(f.vx[it] - 11f) <= 0.4f && abs(f.vy[it] + 9f) <= 0.4f } >= blocks.size * 0.95)
        // The same move with a window of 8 cannot be found: the vectors never leave the window.
        estimator.estimate(plane(texture).data, plane(texture, 11f, -9f).data, width, height, MotionParams.Default, f)
        assertTrue(blocks.all { abs(f.vx[it]) <= MotionParams.Default.range + 0.5f && abs(f.vy[it]) <= MotionParams.Default.range + 0.5f })
    }

    @Test
    fun aStillPictureIsStillAndHasNoCut() {
        val texture = Texture(5)
        val f = field()
        estimator.estimate(plane(texture).data, plane(texture).data, width, height, MotionParams.Default, f)
        assertTrue(f.still)
        assertFalse(f.sceneCut)
        assertTrue(f.vx.all { it == 0f } && f.vy.all { it == 0f })
    }

    @Test
    fun anotherShotIsASceneCut() {
        val f = field()
        estimator.estimate(plane(Texture(1)).data, plane(Texture(2)).data, width, height, MotionParams.Default, f)
        assertTrue(f.sceneCut, "bad blocks ${f.badFraction}, mean error ${f.meanError}")
        assertTrue(f.confidence.all { it == 0f }, "nothing of a cut is trusted")
    }

    @Test
    fun theCheapMeasureOfACutSeesDifferentShotsAndNotTheSameOne() {
        val one = plane(Texture(1))
        assertEquals(0f, MotionEstimator.meanAbsDiff(one.data, plane(Texture(1)).data))
        val across = MotionEstimator.meanAbsDiff(one.data, plane(Texture(2)).data)
        assertTrue(across > 45f, "two unrelated pictures differ by $across grey levels")
    }

    @Test
    fun aMoveWithSomeNoiseIsNotACut() {
        val texture = Texture(9)
        val older = plane(texture)
        val newer = plane(texture, 4f, 1f)
        var seed = 12345
        for (i in newer.data.indices) { // film grain on the newer picture only
            seed = seed * 1103515245 + 12345
            val grain = ((seed ushr 16) and 0xf) - 8
            newer.data[i] = ((newer.data[i].toInt() and 0xff) + grain).coerceIn(0, 255).toByte()
        }
        val f = field()
        estimator.estimate(older.data, newer.data, width, height, MotionParams.Default, f)
        assertFalse(f.sceneCut, "bad blocks ${f.badFraction}, mean error ${f.meanError}")
        val blocks = interior(f)
        assertTrue(blocks.count { abs(f.vx[it] - 4f) <= 0.6f && abs(f.vy[it] - 1f) <= 0.6f } >= blocks.size * 0.9)
    }

    @Test
    fun theAnalysisPictureIsTheSmallGreyCopyOfTheFrame() {
        // A 1920 x 1080 frame is searched at 320 x 176 (180 rounded down to whole blocks); a small one keeps its size.
        val big = LumaExtractor(1920, 1080)
        assertEquals(320, big.width)
        assertEquals(176, big.height)
        val small = LumaExtractor(64, 36)
        assertEquals(64, small.width)
        assertEquals(32, small.height)
        val pixels = ByteBuffer.allocateDirect(64 * 4 * 36).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64 * 36) { // red 200, green 100, blue 50, the ignored byte
            pixels.put(i * 4, 200.toByte()); pixels.put(i * 4 + 1, 100.toByte()); pixels.put(i * 4 + 2, 50.toByte()); pixels.put(i * 4 + 3, 0)
        }
        val plane = small.newPlane()
        small.extract(pixels, 64 * 4, plane)
        assertTrue(plane.data.all { (it.toInt() and 0xff) == 124 }, "luma of (200, 100, 50)")
        assertEquals(0L, plane.stamp % 2, "an even stamp: written completely")
    }

    @Test
    fun a1080pFrameIsSearchedFastEnoughForALiveVideo() {
        val texture = Texture(21)
        val older = plane(texture)
        val newer = plane(texture, 5f, -3f)
        val f = field()
        repeat(5) { estimator.estimate(older.data, newer.data, width, height, MotionParams.Default, f) } // warm up
        val started = System.nanoTime()
        val runs = 10
        repeat(runs) { estimator.estimate(older.data, newer.data, width, height, MotionParams.Default, f) }
        val perPair = (System.nanoTime() - started) / 1e6 / runs
        // A 24 fps video gives 41 ms a pair; the search should use a fraction of it even on a slow machine.
        assertTrue(perPair < 30.0, "a pair took $perPair ms")
    }
}
