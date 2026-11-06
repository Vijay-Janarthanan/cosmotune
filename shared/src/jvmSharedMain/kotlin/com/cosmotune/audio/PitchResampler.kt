package com.cosmotune.audio

import kotlin.math.floor

/**
 * The second half of a pitch shift that leaves the speed alone: a time-stretch by [ratio] first (the desktop's WSOLA
 * stretcher), then this resampler reads [ratio] input frames per output frame, so the length comes back to what it was and
 * every frequency is multiplied by [ratio]. Catmull-Rom interpolation on interleaved float frames; it keeps the frames it
 * still needs between calls, so blocks join without clicks. A [ratio] of 1 passes the samples through untouched.
 */
class PitchResampler(private val channels: Int) {

    @Volatile var ratio: Double = 1.0

    private var carry = FloatArray(0)
    private var carryFrames = 0
    /** Where the next output frame is read, in frames from the start of [carry]. */
    private var position = 1.0
    private var output = FloatArray(0)

    var outputCount: Int = 0
        private set

    fun process(input: FloatArray, count: Int): FloatArray {
        val step = ratio
        if (step == 1.0 && carryFrames == 0) {
            outputCount = count
            return input
        }
        val frames = count / channels
        val total = carryFrames + frames
        if (carry.size < total * channels) carry = carry.copyOf(total * channels)
        System.arraycopy(input, 0, carry, carryFrames * channels, frames * channels)

        val expected = ((total - position) / step).toInt() + 2
        if (output.size < expected * channels) output = FloatArray(expected * channels)
        var written = 0
        var at = position
        // Each output frame needs one frame before its position and two after.
        while (at + 2 < total) {
            val index = floor(at).toInt()
            val t = (at - index).toFloat()
            val i0 = (index - 1).coerceAtLeast(0) * channels
            val i1 = index * channels
            val i2 = (index + 1) * channels
            val i3 = (index + 2) * channels
            for (c in 0 until channels) {
                output[written * channels + c] = catmullRom(carry[i0 + c], carry[i1 + c], carry[i2 + c], carry[i3 + c], t)
            }
            written++
            at += step
        }
        // Keep from one frame before the next read onwards.
        val keepFrom = (floor(at).toInt() - 1).coerceIn(0, total)
        val keep = total - keepFrom
        System.arraycopy(carry, keepFrom * channels, carry, 0, keep * channels)
        carryFrames = keep
        position = at - keepFrom
        outputCount = written * channels
        return output
    }

    fun reset() {
        carryFrames = 0
        position = 1.0
    }

    companion object {
        fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
        }
    }
}
