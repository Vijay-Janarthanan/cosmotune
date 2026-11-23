package com.cosmotune.video.player.sound

import com.cosmotune.video.player.AudioMix
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * The sound mixes a viewer can pick in Watch, applied to interleaved 16-bit PCM in place:
 * - [AudioMix.NIGHT] evens out loud and quiet parts (a compressor with make-up gain), so
 *   explosions do not wake the house and whispers stay audible.
 * - [AudioMix.VOICES] brings dialogue forward: the centre channel of surround sound is raised and
 *   the rest lowered; in stereo, what both channels share (the centre) is raised and what only one
 *   side has is lowered. Mono and four-channel sound have no centre to find and pass unchanged.
 *
 * Keeps its compressor's level between calls, so one instance belongs to one stream; [reset] after
 * a seek or a new stream.
 */
class SoundMixer(sampleRate: Int) {
    private val attack = exp(-1.0 / (0.005 * sampleRate))
    private val release = exp(-1.0 / (0.25 * sampleRate))
    private var envelope = 0.0

    fun reset() { envelope = 0.0 }

    fun process(samples: ShortArray, channels: Int, mix: AudioMix, count: Int = samples.size) {
        if (channels <= 0) return
        when (mix) {
            AudioMix.NORMAL -> Unit
            AudioMix.NIGHT -> night(samples, channels, count)
            AudioMix.VOICES -> voices(samples, channels, count)
        }
    }

    private fun night(samples: ShortArray, channels: Int, count: Int) {
        var frame = 0
        while (frame + channels <= count) {
            var peak = 0.0
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[frame + c].toInt()) / FULL)
            val coefficient = if (peak > envelope) attack else release
            envelope = coefficient * envelope + (1 - coefficient) * peak
            val level = if (envelope < 1e-6) -120.0 else 20 * log10(envelope)
            val over = level - THRESHOLD_DB
            val gainDb = (if (over > 0) over / RATIO - over else 0.0) + MAKEUP_DB
            val gain = 10.0.pow(gainDb / 20)
            for (c in 0 until channels) samples[frame + c] = clip(samples[frame + c] * gain)
            frame += channels
        }
    }

    private fun voices(samples: ShortArray, channels: Int, count: Int) {
        var frame = 0
        when {
            channels == 2 -> while (frame + 2 <= count) {
                val left = samples[frame].toDouble()
                val right = samples[frame + 1].toDouble()
                val mid = (left + right) / 2 * MID_GAIN
                val side = (left - right) / 2 * SIDE_GAIN
                samples[frame] = clip(mid + side)
                samples[frame + 1] = clip(mid - side)
                frame += 2
            }
            // Three channels and 5.0 up: front left, front right, then the centre.
            channels == 3 || channels >= 5 -> while (frame + channels <= count) {
                for (c in 0 until channels) {
                    samples[frame + c] = clip(samples[frame + c] * if (c == CENTRE) CENTRE_GAIN else OTHER_GAIN)
                }
                frame += channels
            }
        }
    }

    private fun clip(value: Double): Short = value.coerceIn(-32768.0, 32767.0).toInt().toShort()

    private companion object {
        const val FULL = 32767.0
        const val THRESHOLD_DB = -30.0
        const val RATIO = 4.0
        const val MAKEUP_DB = 10.0
        const val MID_GAIN = 1.35
        const val SIDE_GAIN = 0.6
        const val CENTRE = 2
        const val CENTRE_GAIN = 1.6
        const val OTHER_GAIN = 0.6
    }
}
