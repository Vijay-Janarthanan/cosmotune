package com.cosmotune.design

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The superellipse outline of `02-design-formulas.md` section 3: n = 2 is a circle, n = 4 the design's squircle. */
class SuperellipseTest {

    private fun outline(w: Float, h: Float, r: Float, n: Float): Pair<FloatArray, Int> {
        val out = FloatArray(Superellipse.BUFFER_SIZE)
        return out to Superellipse.outline(w, h, r, n, out)
    }

    @Test
    fun exponentTwoWithHalfTheSideIsACircle() {
        val (out, count) = outline(100f, 100f, 50f, 2f)
        for (i in 0 until count) {
            assertEquals(50f, hypot(out[2 * i] - 50f, out[2 * i + 1] - 50f), 0.01f, "point $i")
        }
    }

    @Test
    fun exponentFourCornerAtFortyFiveDegrees() {
        // The top-left corner is the fourth of four, so its midpoint is index 3 x 17 + 8. At 45 degrees a squircle corner
        // of radius 10 sits at 10 - 10 x 0.5^(1/4) from each edge.
        val (out, _) = outline(100f, 60f, 10f, 4f)
        val i = 3 * 17 + 8
        assertEquals(1.591f, out[2 * i], 0.01f)
        assertEquals(1.591f, out[2 * i + 1], 0.01f)
    }

    @Test
    fun aRadiusLargerThanHalfTheShortSideIsClamped() {
        val (big, _) = outline(100f, 60f, 80f, 4f)
        val (clamped, _) = outline(100f, 60f, 30f, 4f)
        for (i in big.indices) assertEquals(clamped[i], big[i], 1e-4f, "float $i")
    }

    @Test
    fun theOutlineHasSixtyEightPointsAndTheBufferHoldsThem() {
        val (_, count) = outline(100f, 60f, 10f, 4f)
        assertEquals(68, count)
        assertEquals(136, Superellipse.BUFFER_SIZE)
        assertEquals(16, Superellipse.SEGMENTS)
    }

    @Test
    fun theOutlineStartsOnTheTopEdgeAndGoesClockwise() {
        val (out, count) = outline(100f, 60f, 10f, 4f)
        // First point: top edge above the top-right corner's centre. Last point: top edge right of the top-left corner.
        assertEquals(90f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
        assertEquals(10f, out[2 * (count - 1)], 1e-3f)
        assertEquals(0f, out[2 * (count - 1) + 1], 1e-3f)
        // Point 16 is the end of the top-right corner: on the right edge.
        assertEquals(100f, out[2 * 16], 1e-3f)
        assertEquals(10f, out[2 * 16 + 1], 1e-3f)
        // The bottom-right corner ends on the bottom edge.
        assertEquals(60f, out[2 * (2 * 17 - 1) + 1], 1e-3f)
    }

    @Test
    fun everyPointStaysInsideTheBox() {
        for (n in floatArrayOf(2f, 3f, 4f, 6f)) {
            val (out, count) = outline(120f, 48f, 24f, n)
            for (i in 0 until count) {
                assertTrue(out[2 * i] in -1e-3f..120.001f, "x of point $i at n=$n")
                assertTrue(out[2 * i + 1] in -1e-3f..48.001f, "y of point $i at n=$n")
            }
        }
    }

    @Test
    fun aSquircleReachesFurtherIntoTheCornerThanACircle() {
        // At 45 degrees the n = 4 corner is closer to the box corner than the n = 2 corner of the same radius.
        val (circle, _) = outline(100f, 60f, 20f, 2f)
        val (squircle, _) = outline(100f, 60f, 20f, 4f)
        val i = 3 * 17 + 8
        assertTrue(squircle[2 * i] < circle[2 * i])
        assertTrue(squircle[2 * i + 1] < circle[2 * i + 1])
    }

    @Test
    fun aZeroRadiusIsAPlainRectangle() {
        val (out, count) = outline(100f, 60f, 0f, 4f)
        for (i in 0 until count) {
            val x = out[2 * i]
            val y = out[2 * i + 1]
            assertTrue((x == 0f || x == 100f) && (y == 0f || y == 60f), "point $i is at ($x, $y)")
        }
    }
}
