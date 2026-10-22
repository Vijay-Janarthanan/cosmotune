package com.opencanvas.core.sync

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * Turns the frame sizes of an encoded song into a signal that two encodings of the same music share:
 * how much the encoder had to spend over time, with the slow drift of the bitrate removed.
 *
 * The result is sampled on a fixed grid of [RATE_HZ] so streams with different frame lengths (23 ms
 * AAC-LC against 46 ms HE-AAC) can be compared directly.
 */
object FrameEnvelope {

    /** Samples per second of the envelope. */
    const val RATE_HZ = 50

    private const val DRIFT_WINDOW_SECONDS = 3.0

    fun build(frames: AudioFrames): FloatArray {
        val count = frames.sizes.size
        if (count < 2 || frames.frameSeconds <= 0.0) return FloatArray(0)
        val dt = frames.frameSeconds

        // size on a log scale: loudness and busyness compress to roughly equal steps
        val level = DoubleArray(count) { ln(1.0 + frames.sizes[it]) }

        // remove a centred moving average (edge-replicated) to drop the bitrate drift
        val window = max(3, (DRIFT_WINDOW_SECONDS / dt).toInt())
        val before = window / 2
        val prefix = DoubleArray(count + window)
        for (j in 0 until count + window - 1) {
            prefix[j + 1] = prefix[j] + level[(j - before).coerceIn(0, count - 1)]
        }
        val flat = DoubleArray(count) { level[it] - (prefix[it + window] - prefix[it]) / window }

        // resample onto the common grid by linear interpolation
        val last = (count - 1) * dt
        val step = 1.0 / RATE_HZ
        val samples = ceil(last / step).toInt()
        return FloatArray(samples) { m ->
            val position = m * step / dt
            val index = floor(position).toInt().coerceAtMost(count - 2)
            val fraction = position - index
            (flat[index] + (flat[index + 1] - flat[index]) * fraction).toFloat()
        }
    }
}
