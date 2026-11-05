package com.cosmotune.video.player.smooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A small grey copy of a picture, the only thing the motion search looks at: [width] x [height] bytes of luma, both whole
 * multiples of [MotionEstimator.BLOCK]. [stamp] changes when the plane is rewritten, so a search that was reading it while it
 * was reused can tell and throw its answer away.
 */
class LumaPlane(val width: Int, val height: Int) {
    val data = ByteArray(width * height)

    @Volatile var stamp: Long = 0
}

/**
 * Turns frames of mpv's `rgb0` (red, green, blue, then an ignored byte, little-endian in memory) into [LumaPlane]s of about
 * [MAX_WIDTH] pixels across: each analysis pixel averages up to four samples of its cell. Made once per frame size; the index
 * tables are filled here, so a frame is converted without allocating.
 */
class LumaExtractor(private val srcWidth: Int, private val srcHeight: Int) {
    val width: Int = (min(MAX_WIDTH, srcWidth) / MotionEstimator.BLOCK * MotionEstimator.BLOCK).coerceAtLeast(MotionEstimator.BLOCK)
    val height: Int = ((srcHeight.toLong() * width / srcWidth).toInt() / MotionEstimator.BLOCK * MotionEstimator.BLOCK).coerceAtLeast(MotionEstimator.BLOCK)

    /** The two sample columns and rows of every analysis column and row. */
    private val xs = IntArray(width * 2) { i -> sample(i / 2, i % 2, width, srcWidth) }
    private val ys = IntArray(height * 2) { i -> sample(i / 2, i % 2, height, srcHeight) }

    private fun sample(cell: Int, which: Int, cells: Int, size: Int): Int {
        val position = (cell + 0.25 + 0.5 * which) * size / cells
        return min(size - 1, position.toInt())
    }

    fun newPlane() = LumaPlane(width, height)

    /** Fills [out] from [src] ([srcStride] bytes a row). [src] is read with absolute gets on a view, so a shared buffer is not disturbed. */
    fun extract(src: ByteBuffer, srcStride: Int, out: LumaPlane) {
        val view = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val data = out.data
        out.stamp++ // odd while it is written
        var o = 0
        for (y in 0 until height) {
            val row0 = ys[2 * y] * srcStride
            val row1 = ys[2 * y + 1] * srcStride
            for (x in 0 until width) {
                val a = x shl 1
                val x0 = xs[a] shl 2
                val x1 = xs[a + 1] shl 2
                val sum = luma(view.getInt(row0 + x0)) + luma(view.getInt(row0 + x1)) + luma(view.getInt(row1 + x0)) + luma(view.getInt(row1 + x1))
                data[o++] = (sum shr 2).toByte()
            }
        }
        out.stamp++
    }

    private fun luma(rgb0: Int): Int = ((rgb0 and 0xff) * 77 + ((rgb0 shr 8) and 0xff) * 150 + ((rgb0 shr 16) and 0xff) * 29) shr 8

    companion object {
        /** The analysis picture is this wide at most: a 1080p frame is searched at 320 x 180, a 36th of its pixels. */
        const val MAX_WIDTH = 320
    }
}

/**
 * How the search is tuned: the largest move looked for ([range], in analysis pixels), the mean grey difference up to which a
 * match is trusted ([tolerance], full trust below half of it, none at it), how many badly matched blocks make a scene cut
 * ([cutFraction]) or how large the mean difference of the best matches may be ([cutMad]).
 */
class MotionParams(val range: Int, val tolerance: Float, val cutFraction: Float, val cutMad: Float) {
    companion object {
        val Default = MotionParams(range = 8, tolerance = 14f, cutFraction = 0.6f, cutMad = 28f)
    }
}

/**
 * The answer for one pair of pictures, in blocks of [MotionEstimator.BLOCK] analysis pixels anchored on the newer picture:
 * [vx] and [vy] say how far the content of each block moved from the older picture to the newer one (analysis pixels, positive
 * to the right and down), [confidence] how far to trust that (0 to 1). [sceneCut] means the two pictures are different shots;
 * [still] that nothing moves. Reused from pair to pair: [columns] x [rows] never changes for a size.
 */
class MotionField(val columns: Int, val rows: Int) {
    val vx = FloatArray(columns * rows)
    val vy = FloatArray(columns * rows)
    val confidence = FloatArray(columns * rows)
    var sceneCut = false
    var still = false

    /** The mean grey difference of the best matches (a measure of how well the motion explains the change). */
    var meanError = 0f

    /** Share of blocks with no trustworthy match. */
    var badFraction = 0f
}

/**
 * Block matching between two [LumaPlane]s: for every 8 x 8 block of the newer picture, the displacement within [MotionParams.range]
 * with the smallest sum of absolute differences (SAD), refined to a fraction of a pixel by a parabola through the neighbours.
 * Candidates are tried zero first, then the vectors of the blocks to the left and above, then everything in range; a candidate
 * is dropped as soon as its partial sum passes the best so far. A flat block (zero is already as good as it gets) is not
 * searched. Nothing is allocated in [estimate].
 */
class MotionEstimator {

    /** Fills [out] for [prev] to [cur] (same size, made for [out]). */
    fun estimate(prev: ByteArray, cur: ByteArray, width: Int, height: Int, params: MotionParams, out: MotionField) {
        val cols = out.columns
        val rows = out.rows
        var bad = 0
        var errorSum = 0L
        var zeroSum = 0L
        val range = params.range
        val fullTrust = params.tolerance * 0.5f
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                val px = bx * BLOCK
                val py = by * BLOCK
                val index = by * cols + bx
                val zero = sad(cur, prev, width, px, py, 0, 0, Int.MAX_VALUE)
                zeroSum += zero
                var bestDx = 0
                var bestDy = 0
                var best = zero
                if (zero > FLAT_SAD) {
                    // The window keeps the displaced block inside the picture.
                    val minDx = max(-range, -px)
                    val maxDx = min(range, width - BLOCK - px)
                    val minDy = max(-range, -py)
                    val maxDy = min(range, height - BLOCK - py)
                    // Neighbours first: the same object usually moves the same way, and a good early bound prunes the rest.
                    if (bx > 0) {
                        val dx = Math.round(-out.vx[index - 1]).coerceIn(minDx, maxDx)
                        val dy = Math.round(-out.vy[index - 1]).coerceIn(minDy, maxDy)
                        val s = sad(cur, prev, width, px, py, dx, dy, best)
                        if (s < best) { best = s; bestDx = dx; bestDy = dy }
                    }
                    if (by > 0) {
                        val above = index - cols
                        val dx = Math.round(-out.vx[above]).coerceIn(minDx, maxDx)
                        val dy = Math.round(-out.vy[above]).coerceIn(minDy, maxDy)
                        val s = sad(cur, prev, width, px, py, dx, dy, best)
                        if (s < best) { best = s; bestDx = dx; bestDy = dy }
                    }
                    for (dy in minDy..maxDy) {
                        for (dx in minDx..maxDx) {
                            if (dx == 0 && dy == 0) continue
                            val s = sad(cur, prev, width, px, py, dx, dy, best)
                            if (s < best) { best = s; bestDx = dx; bestDy = dy }
                        }
                    }
                    // Noise in a flat area must not invent motion: a real move is clearly better than standing still.
                    if (zero - best <= BLOCK_PIXELS) { best = zero; bestDx = 0; bestDy = 0 }
                }
                var fx = bestDx.toFloat()
                var fy = bestDy.toFloat()
                if ((bestDx != 0 || bestDy != 0) && best > 0) {
                    fx += refine(cur, prev, width, height, px, py, bestDx, bestDy, 1, 0, best, range)
                    fy += refine(cur, prev, width, height, px, py, bestDx, bestDy, 0, 1, best, range)
                }
                val mad = best.toFloat() / BLOCK_PIXELS
                errorSum += best
                val trust = when {
                    mad <= fullTrust -> 1f
                    mad >= params.tolerance -> 0f
                    else -> 1f - (mad - fullTrust) / (params.tolerance - fullTrust)
                }
                if (trust <= 0f) bad++
                // Content moved by the opposite of the displacement that finds it in the older picture.
                out.vx[index] = -fx
                out.vy[index] = -fy
                out.confidence[index] = trust
            }
        }
        val blocks = cols * rows
        val pixels = blocks.toLong() * BLOCK_PIXELS
        out.badFraction = bad.toFloat() / blocks
        out.meanError = errorSum.toFloat() / pixels
        out.still = zeroSum.toFloat() / pixels < STILL_MAD
        out.sceneCut = !out.still && (out.badFraction > params.cutFraction || out.meanError > params.cutMad)
        if (out.sceneCut) java.util.Arrays.fill(out.confidence, 0f)
    }

    /** Sum of absolute differences of the block of [cur] at ([x], [y]) and the block of [prev] displaced by ([dx], [dy]); stops once it passes [limit]. */
    private fun sad(cur: ByteArray, prev: ByteArray, width: Int, x: Int, y: Int, dx: Int, dy: Int, limit: Int): Int {
        var sum = 0
        var c = y * width + x
        var p = (y + dy) * width + x + dx
        for (row in 0 until BLOCK) {
            for (i in 0 until BLOCK) {
                sum += abs((cur[c + i].toInt() and 0xff) - (prev[p + i].toInt() and 0xff))
            }
            if (sum >= limit) return sum
            c += width
            p += width
        }
        return sum
    }

    /** The sub-pixel offset along ([ax], [ay]) from the parabola through the SAD at -1, 0 and +1 of the best displacement; 0 where a neighbour is out of the picture or range. */
    private fun refine(
        cur: ByteArray, prev: ByteArray, width: Int, height: Int, x: Int, y: Int, dx: Int, dy: Int, ax: Int, ay: Int, centre: Int, range: Int,
    ): Float {
        val lx = dx - ax
        val ly = dy - ay
        val hx = dx + ax
        val hy = dy + ay
        if (abs(lx) > range || abs(ly) > range || abs(hx) > range || abs(hy) > range) return 0f
        if (x + lx < 0 || x + hx < 0 || y + ly < 0 || y + hy < 0) return 0f
        if (x + lx + BLOCK > width || x + hx + BLOCK > width || y + ly + BLOCK > height || y + hy + BLOCK > height) return 0f
        val a = sad(cur, prev, width, x, y, lx, ly, Int.MAX_VALUE)
        val b = sad(cur, prev, width, x, y, hx, hy, Int.MAX_VALUE)
        val denominator = 2f * (a - 2f * centre + b)
        if (denominator <= 0f) return 0f
        return ((a - b) / denominator).coerceIn(-0.5f, 0.5f)
    }

    companion object {
        /** The mean absolute difference of two equal planes, in grey levels (0 to 255): a cheap measure of a cut. */
        fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
            var sum = 0L
            for (i in a.indices) sum += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
            return sum.toFloat() / a.size
        }

        const val BLOCK = 8
        private const val BLOCK_PIXELS = BLOCK * BLOCK

        /** A block whose SAD at rest is this small has nothing to search for (a mean difference of one grey level). */
        private const val FLAT_SAD = BLOCK_PIXELS

        /** The whole picture changes by less than this many grey levels on average: nothing moves. */
        private const val STILL_MAD = 0.8f
    }
}
