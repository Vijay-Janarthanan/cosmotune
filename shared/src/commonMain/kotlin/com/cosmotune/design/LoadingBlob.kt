package com.cosmotune.design

import kotlin.math.floor

/**
 * The loading shape of the B+C spec (`@keyframes blob` in `components-v3bc.css`): a blob, `border-radius: 30% 70% 62% 38% /
 * 38% 34% 66% 62%`, that becomes a circle half way through the loop while it grows from 0.9 to 1.05 of its size, and turns
 * once in [PERIOD_MS]. Every half of the loop eases in and out.
 *
 * [at] writes one frame into a caller-owned array: no allocation per frame.
 */
object LoadingBlob {
    const val PERIOD_MS = 1100

    /** Slots of the array [at] fills. */
    const val ROTATION_DEG = 0
    const val SCALE = 1

    /** Eight slots from here: horizontal radii of top-left, top-right, bottom-right, bottom-left, then the vertical ones, as fractions of the width / height. */
    const val RADII = 2
    const val SIZE = RADII + 8

    private const val SCALE_ENDS = 0.9f
    private const val SCALE_MIDDLE = 1.05f
    private val BLOB = floatArrayOf(0.30f, 0.70f, 0.62f, 0.38f, 0.38f, 0.34f, 0.66f, 0.62f)

    /** Frame at [phase] (0..1, one turn; any other value wraps around) into [out], which needs [SIZE] slots. */
    fun at(phase: Float, out: FloatArray) {
        val p = phase - floor(phase)
        val wholeTurn = phase >= 1f && p == 0f   // 1.0 is the end of the turn, not the start of the next one
        val half = if (wholeTurn) 1f else if (p < 0.5f) p * 2f else (p - 0.5f) * 2f
        val e = half * half * (3f - 2f * half)   // ease in and out
        val first = wholeTurn || p >= 0.5f
        val circle = if (first) 1f - e else e      // 0 = the blob, 1 = a circle
        out[ROTATION_DEG] = if (wholeTurn) 360f else if (p < 0.5f) 180f * e else 180f + 180f * e
        out[SCALE] = SCALE_ENDS + (SCALE_MIDDLE - SCALE_ENDS) * circle
        for (i in 0 until 8) out[RADII + i] = BLOB[i] + (0.5f - BLOB[i]) * circle
    }
}
