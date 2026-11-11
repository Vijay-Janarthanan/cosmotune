package com.cosmotune.audio

import kotlin.math.*

/** Onset comparison inspired by AETHER/PODIUM SoundAlignment (GPL-3.0; see NOTICE).
 * A result is a small encoder-padding correction, never a licence to match different edits. */
object AcousticAlignment {
    const val STEP_MS = 10L
    const val WINDOW_MS = 2000L
    const val MARGIN_MS = 500L

    /**
     * Whether a better-stream handover may move its seek by a measured offset.
     *
     * Off. The comparison itself is covered by synthetic fixtures, but no real pair of renditions
     * has been measured on either platform, and a wrong answer is audible: the song jumps by up to
     * half a second at the moment it changes quality. Until real recordings show it helping, the
     * phone does not decode for it and the desktop neither meters nor pre-rolls the new copy, so
     * the handover is exactly what it was before this was added.
     */
    val applyToUpgrades: Boolean = false

    /** copy[p + result] contains the sound at reference[p]. Null means insufficient evidence. */
    fun offset(reference: FloatArray, copy: FloatArray): Long? {
        val margin = (MARGIN_MS / STEP_MS).toInt()
        if (reference.size < 100 || copy.size != reference.size + 2 * margin ||
            reference.any { !it.isFinite() || it < 0 } || copy.any { !it.isFinite() || it < 0 }) return null
        fun onsets(values: FloatArray) = DoubleArray(values.size).also { out ->
            for (i in 1 until values.size) out[i] = max(0.0, ln(values[i] + 1e-4) - ln(values[i - 1] + 1e-4))
        }
        val a = onsets(reference); val b = onsets(copy)
        val meanA = a.average()
        val energyA = a.sumOf { (it - meanA).pow(2) }
        if (energyA < 1e-8) return null
        val scores = DoubleArray(2 * margin + 1) { lag ->
            val meanB = (a.indices.sumOf { b[it + lag] }) / a.size
            var cross = 0.0; var energyB = 0.0
            for (i in a.indices) { val y = b[i + lag] - meanB; cross += (a[i] - meanA) * y; energyB += y * y }
            if (energyB < 1e-8) 0.0 else cross / sqrt(energyA * energyB)
        }
        val best = scores.indices.maxBy { scores[it] }
        val rival = scores.indices.filter { abs(it - best) > 3 }.maxOfOrNull { scores[it] } ?: 0.0
        return if (scores[best] >= .65 && scores[best] - rival >= .1) (best - margin) * STEP_MS else null
    }

    /**
     * Loudness per [STEP_MS] of song time, from PCM a decoder has already produced. No DSP change
     * and no extra network read: it only listens to what was decoded anyway.
     *
     * Bins are keyed by media time, so blocks must be reported with the song position of their
     * first frame (after any seek trim). A block that starts before the previous one ended means
     * the decoder went back (a seek, a retry): everything measured so far is dropped rather than
     * mixed with the replayed audio. A forward jump only leaves a hole, which [read] refuses.
     * Safe to feed from a decoder thread while another thread reads.
     */
    class Envelope(private val capacityBins: Int = 2400) {
        private class Bin(val expected: Int) { var square = 0.0; var count = 0 }
        private val bins = java.util.TreeMap<Long, Bin>()
        private var lastEndMs = Double.NEGATIVE_INFINITY

        @Synchronized fun add(samples: FloatArray, count: Int, startMs: Double, sampleRate: Int, channels: Int = 1) {
            if (sampleRate <= 0 || channels <= 0 || !startMs.isFinite() || startMs < 0) return
            val frames = min(count, samples.size).coerceAtLeast(0) / channels
            if (frames == 0) return
            if (startMs < lastEndMs - STEP_MS) bins.clear()
            var frame = 0
            while (frame < frames) {
                val time = floor((startMs + frame * 1000.0 / sampleRate) / STEP_MS).toLong()
                val bin = bins.getOrPut(time) { Bin(expected = (sampleRate * STEP_MS / 1000).toInt()) }
                val end = ceil(((time + 1) * STEP_MS - startMs) * sampleRate / 1000.0).toInt().coerceIn(frame + 1, frames)
                var square = 0.0
                // Summed channel energy avoids cancellation of out-of-phase stereo.
                for (index in frame * channels until end * channels) { val x = samples[index]; if (x.isFinite()) square += x * x }
                bin.square += square / channels; bin.count += end - frame
                frame = end
            }
            lastEndMs = startMs + frames * 1000.0 / sampleRate
            while (bins.size > capacityBins) bins.pollFirstEntry()
        }

        /** Drops everything measured, for a decoder that is about to start somewhere else. */
        @Synchronized fun clear() { bins.clear(); lastEndMs = Double.NEGATIVE_INFINITY }

        /** [steps] consecutive bins from [startMs], or null if any is missing, partial or doubled. */
        @Synchronized fun read(startMs: Long, steps: Int): FloatArray? {
            if (steps <= 0 || startMs < 0) return null
            val from = Math.floorDiv(startMs, STEP_MS)
            return FloatArray(steps) { index ->
                val bin = bins[from + index] ?: return null
                if (bin.count < bin.expected * .8 || bin.count > bin.expected * 1.4) return null
                sqrt(bin.square / bin.count).toFloat()
            }
        }
    }

    /**
     * Where to start the new copy so it continues the sound of the old one: [reference] is the old
     * copy's envelope and [copy] the new one's, both on their own media clocks, compared over the
     * two seconds that end 600 ms before [positionMs]. Null when either is incomplete or the
     * evidence is ambiguous, which callers treat as "seek to [positionMs] as before".
     */
    fun alignedOffset(reference: Envelope, copy: Envelope, positionMs: Long): Long? {
        val start = comparisonStartMs(positionMs)
        val a = reference.read(start, (WINDOW_MS / STEP_MS).toInt()) ?: return null
        val b = copy.read(start - MARGIN_MS, ((WINDOW_MS + 2 * MARGIN_MS) / STEP_MS).toInt()) ?: return null
        return offset(a, b)
    }

    /** The start of the comparison window for a handover at [positionMs], on a [STEP_MS] boundary. */
    fun comparisonStartMs(positionMs: Long): Long = ((positionMs - 2600).coerceAtLeast(MARGIN_MS) / STEP_MS) * STEP_MS
}
