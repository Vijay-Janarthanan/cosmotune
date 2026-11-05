package com.cosmotune.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** One instance per decoded track, so overlapping songs retain their own gain. */
class LoudnessNormalizer {
    private var gain = 1f
    private var reduction = 1f
    private var started = false

    fun process(samples: FloatArray, count: Int, channels: Int, sampleRate: Int, loudnessDb: Double?, enabled: Boolean) {
        if (count <= 0 || channels <= 0 || sampleRate <= 0) return
        val target = if (enabled && loudnessDb != null && loudnessDb.isFinite()) {
            10.0.pow((-loudnessDb).coerceIn(-15.0, 3.0) / 20.0).toFloat()
        } else 1f
        if (!started) { gain = target; started = true }
        if (target == 1f && abs(gain - 1f) < 0.00001f && reduction > 0.99999f) {
            gain = 1f
            reduction = 1f
            return
        }
        val glide = (1.0 - exp(-1.0 / (0.04 * sampleRate))).toFloat()
        val release = (1.0 - exp(-1.0 / (0.08 * sampleRate))).toFloat()
        val end = minOf(count, samples.size)
        var i = 0
        while (i + channels <= end) {
            gain += (target - gain) * glide
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[i + c]) * gain)
            val wanted = if (peak > 0.985f) 0.985f / peak else 1f
            reduction = if (wanted < reduction) wanted else reduction + (wanted - reduction) * release
            for (c in 0 until channels) samples[i + c] *= gain * reduction
            i += channels
        }
    }
}
