package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The wavy scrubber's played stretch (`v2.js` `V2.drawWaves`, scaled to dp). The caller draws the points as one rounded
 * stroke, a straight track after the handle and the handle itself; all of that reuses one buffer and one `Path`.
 */
object Wave {
    const val HEIGHT_DP = 28f
    const val AMPLITUDE_DP = 3.2f
    const val WAVELENGTH_DP = 26f
    const val STROKE_DP = 4f

    /** Space between the end of the played wave and the handle. */
    const val GAP_BEFORE_DP = 5f

    /** Space between the handle and the start of the unplayed track. */
    const val GAP_AFTER_DP = 7f
    const val STEP_DP = 2f
    const val HANDLE_W_DP = 4f
    const val HANDLE_H_DP = 24f

    /** The wave's phase advances about this fast while playing (B+C spec); with reduced motion, or paused, it stands still. */
    const val PHASE_RAD_PER_S = 3.6f

    /** Points a buffer needs for a scrubber [width] wide at [step] spacing: `ceil(width / step) + 2`. */
    fun pointCapacity(width: Float, step: Float = STEP_DP): Int = ceil(width / step).toInt() + 2

    /**
     * Writes (x, y) pairs into [out] (so it holds `out.size / 2` points), from [startX] every [step] up to
     * `progress * width - endGap`, with `y = midY + amplitude * sin(2 pi x / wavelength + phase)`. Stops when [out] is full.
     * Returns the number of points; a [progress] of zero or less gives none. Never allocates.
     */
    fun playedPoints(
        width: Float,
        progress: Float,
        amplitude: Float,
        wavelength: Float,
        phase: Float,
        midY: Float,
        startX: Float,
        endGap: Float,
        step: Float,
        out: FloatArray,
    ): Int {
        if (progress <= 0f || step <= 0f || wavelength <= 0f) return 0
        val limit = progress * width - endGap
        val capacity = out.size / 2
        val k = (2.0 * PI / wavelength).toFloat()
        var n = 0
        while (n < capacity) {
            // Computed from the index, so the error does not grow along the wave.
            val x = startX + n * step
            if (x > limit) break
            out[2 * n] = x
            out[2 * n + 1] = midY + amplitude * sin(k * x + phase)
            n++
        }
        return n
    }
}
