package com.cosmotune.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private val out = FloatArray(4)  // for the (width, height, angle) overload

    private fun roundedOut() = out.toList().map { Math.round(it * 1000f) / 1000f }

    @Test fun lineOverloadNinetyDegreesRunsLeftToRight() {
        CssGradient.line(200f, 100f, 90f, out)
        assertEquals(listOf(0f, 50f, 200f, 50f), roundedOut())
    }

    @Test fun lineOverloadOneEightyRunsTopToBottom() {
        CssGradient.line(200f, 100f, 180f, out)
        assertEquals(listOf(100f, 0f, 100f, 100f), roundedOut())
    }

    @Test fun lineOverloadZeroRunsBottomToTop() {
        CssGradient.line(200f, 100f, 0f, out)
        assertEquals(listOf(100f, 100f, 100f, 0f), roundedOut())
    }

    @Test fun lineOverloadAHundredDegreesIsCenteredAndAsLongAsTheCssGradientLine() {
        val w = 300f
        val h = 64f
        CssGradient.line(w, h, 100f, out)
        // The gradient line of CSS has length |w sin a| + |h cos a| and passes through the centre.
        val a = Math.toRadians(100.0)
        val length = abs(w * Math.sin(a)) + abs(h * Math.cos(a))
        assertEquals(w / 2f, (out[0] + out[2]) / 2f, 1e-3f)
        assertEquals(h / 2f, (out[1] + out[3]) / 2f, 1e-3f)
        val dx = (out[2] - out[0]).toDouble()
        val dy = (out[3] - out[1]).toDouble()
        assertEquals(length, Math.hypot(dx, dy), 1e-3)
        // 100 degrees points right and slightly down (y grows downwards).
        assertTrue(dx > 0.0 && dy > 0.0)
    }
}
