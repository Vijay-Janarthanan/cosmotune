package com.cosmotune.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CSS gradient angles (`linear-gradient(100deg, ...)`) as the two end points Compose wants. */
class CssGradientTest {
    private val out = FloatArray(4)

    private fun rounded() = out.toList().map { Math.round(it * 1000f) / 1000f }

    @Test fun ninetyDegreesRunsLeftToRightThroughTheMiddle() {
        CssGradient.line(200f, 100f, 90f, out)
        assertEquals(listOf(0f, 50f, 200f, 50f), rounded())
    }

    @Test fun oneEightyDegreesRunsTopToBottom() {
        CssGradient.line(200f, 100f, 180f, out)
        assertEquals(listOf(100f, 0f, 100f, 100f), rounded())
    }

    @Test fun zeroDegreesRunsBottomToTop() {
        CssGradient.line(200f, 100f, 0f, out)
        assertEquals(listOf(100f, 100f, 100f, 0f), rounded())
    }

    @Test fun aHundredDegreesIsCenteredAndAsLongAsTheCssGradientLine() {
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
