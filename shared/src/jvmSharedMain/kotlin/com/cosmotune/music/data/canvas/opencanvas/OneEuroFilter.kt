package com.opencanvas.core.filter

import kotlin.math.PI
import kotlin.math.abs

/**
 * Low-pass filter component for the 1-Euro Filter.
 */
private class LowPassFilter(private var alpha: Double = 0.0) {
    private var s: Double? = null

    fun filter(value: Double, alpha: Double): Double {
        this.alpha = alpha
        val prev = s
        val result = if (prev == null) value else alpha * value + (1.0 - alpha) * prev
        s = result
        return result
    }

    fun lastValue(): Double? = s

    fun reset() {
        s = null
    }
}

/**
 * 1-Euro Filter for smooth, jitter-free, zero-lag camera tracking.
 *
 * Parameters:
 * [minCutoff]: Minimum cutoff frequency (Hz). Lower values reduce jitter during slow movements. Default 1.0.
 * [beta]: Cutoff slope parameter. Higher values reduce lag during fast movements. Default 0.007.
 * [dCutoff]: Cutoff frequency for derivative filtering (Hz). Default 1.0.
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val dCutoff: Double = 1.0,
) {
    private var xFilter = LowPassFilter()
    private var dxFilter = LowPassFilter()
    private var lastTime: Double? = null

    private fun alpha(rate: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        val te = 1.0 / rate
        return 1.0 / (1.0 + tau / te)
    }

    /**
     * Filters the raw input value at timestamp [t] (in seconds).
     */
    fun filter(x: Double, t: Double): Double {
        val prevTime = lastTime
        if (prevTime == null) {
            lastTime = t
            return xFilter.filter(x, 1.0)
        }

        val dt = (t - prevTime).coerceAtLeast(1e-4)
        lastTime = t
        val rate = 1.0 / dt

        // Estimate derivative of the signal
        val lastX = xFilter.lastValue() ?: x
        val dx = (x - lastX) * rate
        val edx = dxFilter.filter(dx, alpha(rate, dCutoff))

        // Dynamic cutoff frequency based on velocity
        val cutoff = minCutoff + beta * abs(edx)
        return xFilter.filter(x, alpha(rate, cutoff))
    }

    /**
     * Resets internal filter state (e.g., when a scene cut happens).
     */
    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastTime = null
    }
}
