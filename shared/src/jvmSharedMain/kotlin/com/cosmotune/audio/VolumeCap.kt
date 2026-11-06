package com.cosmotune.audio

/**
 * The maximum-volume cap as a last gain stage: every sample is scaled by the cap, which moves to a new value over
 * [RAMP_SAMPLES] samples rather than in one step, so dragging the slider does not click. At a cap of 1 it returns before
 * touching a sample, which keeps an untouched chain bit-exact.
 */
class VolumeCap {
    private var current = 1f

    fun process(samples: FloatArray, count: Int, target: Float) {
        val goal = target.coerceIn(0f, 1f)
        if (goal >= 1f && current >= 1f) return
        var gain = current
        val step = (goal - gain) / RAMP_SAMPLES
        for (i in 0 until count) {
            if (gain != goal) {
                gain += step
                if ((step > 0 && gain > goal) || (step < 0 && gain < goal)) gain = goal
            }
            samples[i] *= gain
        }
        current = gain
    }

    fun reset(target: Float) {
        current = target.coerceIn(0f, 1f)
    }

    private companion object {
        const val RAMP_SAMPLES = 2_048f
    }
}
