package com.cosmotune.audio

import kotlin.math.*

/** Onset comparison inspired by AETHER/PODIUM SoundAlignment (GPL-3.0; see NOTICE).
 * A result is a small encoder-padding correction, never a licence to match different edits. */
object AcousticAlignment {
    const val STEP_MS = 10L
    const val WINDOW_MS = 2000L
    const val MARGIN_MS = 500L

    /** copy[p + result] contains the sound at reference[p]. Null means insufficient evidence. */
    fun offset(reference: FloatArray, copy: FloatArray): Long? {
        if (reference.size < 100 || copy.size != reference.size + 100 ||
            reference.any { !it.isFinite() || it < 0 } || copy.any { !it.isFinite() || it < 0 }) return null
        fun onsets(values: FloatArray) = DoubleArray(values.size).also { out ->
            for (i in 1 until values.size) out[i] = max(0.0, ln(values[i] + 1e-4) - ln(values[i - 1] + 1e-4))
        }
        val a = onsets(reference); val b = onsets(copy)
        val meanA = a.average()
        val energyA = a.sumOf { (it - meanA).pow(2) }
        if (energyA < 1e-8) return null
        val scores = DoubleArray(101) { lag ->
            val meanB = (a.indices.sumOf { b[it + lag] }) / a.size
            var cross = 0.0; var energyB = 0.0
            for (i in a.indices) { val y = b[i + lag] - meanB; cross += (a[i] - meanA) * y; energyB += y * y }
            if (energyB < 1e-8) 0.0 else cross / sqrt(energyA * energyB)
        }
        val best = scores.indices.maxBy { scores[it] }
        val rival = scores.indices.filter { abs(it - best) > 3 }.maxOfOrNull { scores[it] } ?: 0.0
        return if (scores[best] >= .65 && scores[best] - rival >= .1) (best - 50) * STEP_MS else null
    }

    /** Exact media-time bins from already decoded PCM. No DSP changes or extra network reads. */
    class Envelope {
        private data class Bin(var square: Double = 0.0, var count: Int = 0, var expected: Int = 0)
        private val bins = java.util.TreeMap<Long, Bin>()
        @Synchronized fun add(samples: FloatArray, count: Int, startMs: Double, sampleRate: Int, channels: Int = 1) {
            if (sampleRate <= 0 || channels <= 0 || !startMs.isFinite()) return
            val frames = min(count, samples.size) / channels
            var frame = 0
            while (frame < frames) {
                val time = floor((startMs + frame * 1000.0 / sampleRate) / STEP_MS).toLong()
                val bin = bins.getOrPut(time) { Bin(expected = sampleRate / 100) }
                val end = ceil(((time + 1) * STEP_MS - startMs) * sampleRate / 1000.0).toInt().coerceIn(frame + 1, frames)
                var square = 0.0
                // Summed channel energy avoids cancellation of out-of-phase stereo.
                for (index in frame * channels until end * channels) { val x = samples[index]; if (x.isFinite()) square += x * x }
                bin.square += square / channels; bin.count += end - frame
                frame = end
            }
            while (bins.size > 2400) bins.pollFirstEntry()
        }
        @Synchronized fun read(startMs: Long, steps: Int): FloatArray? {
            val from = Math.floorDiv(startMs, STEP_MS)
            return FloatArray(steps) { index ->
                val bin = bins[from + index] ?: return null
                if (bin.count < bin.expected * .8 || bin.count > bin.expected * 1.4) return null
                sqrt(bin.square / bin.count).toFloat()
            }
        }
    }
}
