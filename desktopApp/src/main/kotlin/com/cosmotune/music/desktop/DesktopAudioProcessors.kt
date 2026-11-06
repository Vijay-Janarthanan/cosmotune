package com.cosmotune.music.desktop

import kotlin.math.abs

/**
 * Headphone spatial audio on the desktop: the shared [com.cosmotune.audio.SpatialDsp] (bs2b crossfeed, virtual speakers,
 * mono and binaural surround, ported from Mochy Music) with the listener's preset from Extras. It replaced a mid/side
 * widener that was only a stand-in.
 */
internal class DesktopSpatialAudio(private val channels: Int, sampleRate: Int) {

    @Volatile
    var enabled: Boolean = false

    private val dsp = com.cosmotune.audio.SpatialDsp(sampleRate, channels)

    /** Stereo, or a 3- to 8-channel mix for the binaural surround option. */
    val applies: Boolean = dsp.applies

    /** Renders [count] interleaved samples where they lie. */
    fun process(samples: FloatArray, count: Int) {
        if (!applies) return
        val extras = com.cosmotune.settings.Extras.settings
        dsp.process(samples, count, enabled, extras.spatialPreset.value, extras.spatialSurround.value)
    }

    fun reset() = dsp.reset()
}

/** Skips long silences, after Media3's `SilenceSkippingAudioProcessor`. */
internal class DesktopSilenceSkipper(private val channels: Int, sampleRate: Int) {

    @Volatile
    var enabled: Boolean = false

    private val minSilenceFrames = (sampleRate * MIN_SILENCE_US / 1_000_000L).toInt()
    private val keptFrames = (sampleRate * KEPT_EACH_END_US / 1_000_000L).toInt().coerceAtLeast(1)

    /** Silence seen so far in a run that may yet turn out to be short. */
    private var pending = FloatArray(minSilenceFrames * channels)
    private var pendingFrames = 0

    /** The tail of a run being skipped, kept so its end can be heard. */
    private var tail = FloatArray(keptFrames * channels)
    private var tailFrames = 0
    private var tailWrite = 0

    private var skipping = false
    private var output = FloatArray(0)

    /** How much of the array [process] returned belongs to this call. */
    var outputCount: Int = 0
        private set

    /** How much has been dropped, in frames — the position clock needs it. */
    var skippedFrames: Long = 0L
        private set

    fun process(samples: FloatArray, count: Int): FloatArray {
        if (!enabled) {
            outputCount = count
            return samples
        }
        grow(count + (pendingFrames + keptFrames) * channels)
        var produced = 0
        var index = 0

        while (index + channels <= count) {
            if (isSilent(samples, index)) {
                if (skipping) {
                    // Only the last [keptFrames] of a skipped run matter.
                    val at = tailWrite * channels
                    System.arraycopy(samples, index, tail, at, channels)
                    tailWrite = (tailWrite + 1) % keptFrames
                    tailFrames = minOf(tailFrames + 1, keptFrames)
                    skippedFrames++
                } else {
                    System.arraycopy(samples, index, pending, pendingFrames * channels, channels)
                    pendingFrames++
                    if (pendingFrames >= minSilenceFrames) {
                        // Long enough to be a gap.
                        produced = emit(pending, keptFrames * channels, produced, attenuate = true)
                        skippedFrames += (pendingFrames - keptFrames).toLong()
                        pendingFrames = 0
                        tailFrames = 0
                        tailWrite = 0
                        skipping = true
                    }
                }
            } else {
                produced = when {
                    skipping -> emitTail(produced)
                    // A short run of quiet is part of the music; it goes out exactly as it came in.
                    pendingFrames > 0 -> emit(pending, pendingFrames * channels, produced, attenuate = false)
                    else -> produced
                }
                pendingFrames = 0
                skipping = false
                grow(produced + channels)
                System.arraycopy(samples, index, output, produced, channels)
                produced += channels
            }
            index += channels
        }

        outputCount = produced
        return output
    }

    private fun emitTail(produced: Int): Int {
        if (tailFrames == 0) return produced
        grow(produced + tailFrames * channels)
        var at = produced
        // The ring holds the most recent frames; oldest first on the way out.
        val start = if (tailFrames < keptFrames) 0 else tailWrite
        repeat(tailFrames) { step ->
            val frame = (start + step) % keptFrames
            System.arraycopy(tail, frame * channels, output, at, channels)
            at += channels
        }
        for (index in produced until at) output[index] *= KEPT_VOLUME
        tailFrames = 0
        tailWrite = 0
        return at
    }

    private fun emit(source: FloatArray, length: Int, produced: Int, attenuate: Boolean): Int {
        grow(produced + length)
        System.arraycopy(source, 0, output, produced, length)
        if (attenuate) for (index in produced until produced + length) output[index] *= KEPT_VOLUME
        return produced + length
    }

    private fun isSilent(samples: FloatArray, at: Int): Boolean {
        for (channel in 0 until channels) {
            if (abs(samples[at + channel]) > THRESHOLD) return false
        }
        return true
    }

    private fun grow(required: Int) {
        if (output.size < required) output = output.copyOf(maxOf(required, output.size * 2))
    }

    fun reset() {
        pendingFrames = 0
        tailFrames = 0
        tailWrite = 0
        skipping = false
        skippedFrames = 0
    }

    private companion object {
        /** Android's own minimum, an order of magnitude above Media3's default. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Media3's retention ratio against that minimum, split across both ends. */
        const val KEPT_EACH_END_US = 100_000L

        /** `DEFAULT_SILENCE_THRESHOLD_LEVEL` of 1024, on a 16-bit scale. */
        const val THRESHOLD = 1024f / 32_768f

        /** `DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE` of 10. */
        const val KEPT_VOLUME = 0.1f
    }
}
