package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The CSS `linear-gradient(<angle>)` line: direction (sin a, -cos a), as long as the box needs to reach the corners. */
class CssGradientTest {
    private fun line(angle: Float, w: Float, h: Float): FloatArray = FloatArray(4).also { CssGradient.endpoints(angle, w, h, it) }

    private fun assertLine(expected: FloatArray, actual: FloatArray, what: String) {
        for (i in 0 until 4) assertEquals(expected[i], actual[i], 0.01f, "$what [$i]")
    }

    @Test
    fun ninetyDegreesRunsLeftToRightAlongTheMiddle() = assertLine(floatArrayOf(0f, 20f, 100f, 20f), line(90f, 100f, 40f), "90")

    @Test
    fun oneEightyRunsTopToBottomAlongTheMiddle() = assertLine(floatArrayOf(50f, 0f, 50f, 40f), line(180f, 100f, 40f), "180")

    @Test
    fun zeroRunsBottomToTop() = assertLine(floatArrayOf(50f, 40f, 50f, 0f), line(0f, 100f, 40f), "0")

    @Test
    fun oneSixtyOnASquareReachesPastTheEdgesSoTheCornersTakeTheEndColours() {
        // d = (sin 160, -cos 160) = (0.3420, 0.9397); length = 100 (0.3420 + 0.9397) = 128.17; half = 64.09
        assertLine(floatArrayOf(28.08f, -10.22f, 71.92f, 110.22f), line(160f, 100f, 100f), "160")
    }

    @Test
    fun theLineAlwaysPassesThroughTheCentre() {
        for (angle in floatArrayOf(10f, 100f, 135f, 145f, 200f, 310f)) {
            val l = line(angle, 120f, 56f)
            assertEquals(60f, (l[0] + l[2]) / 2f, 0.001f, "cx at $angle")
            assertEquals(28f, (l[1] + l[3]) / 2f, 0.001f, "cy at $angle")
        }
    }
}
