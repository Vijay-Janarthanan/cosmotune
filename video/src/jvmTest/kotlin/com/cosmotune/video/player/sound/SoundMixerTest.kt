package com.cosmotune.video.player.sound

import com.cosmotune.video.player.AudioMix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundMixerTest {
    private val rate = 48_000

    private fun tone(level: Double, frames: Int, channels: Int, pick: (Int) -> Double = { 1.0 }): ShortArray =
        ShortArray(frames * channels) { i ->
            val frame = i / channels
            val channel = i % channels
            (level * pick(channel) * 32767 * kotlin.math.sin(2 * Math.PI * 440 * frame / rate)).toInt().toShort()
        }

    private fun peak(samples: ShortArray, from: Int = samples.size / 2, channel: Int? = null, channels: Int = 1): Double =
        (from until samples.size).filter { channel == null || it % channels == channel }.maxOf { abs(samples[it].toInt()) } / 32767.0

    @Test fun normalLeavesTheSoundAlone() {
        val input = tone(0.5, 4_800, 2)
        val output = input.copyOf()
        SoundMixer(rate).process(output, 2, AudioMix.NORMAL)
        assertTrue(input.contentEquals(output))
    }

    @Test fun nightBringsLoudAndQuietCloser() {
        val quiet = tone(0.03, 48_000, 2).also { SoundMixer(rate).process(it, 2, AudioMix.NIGHT) }
        val loud = tone(0.9, 48_000, 2).also { SoundMixer(rate).process(it, 2, AudioMix.NIGHT) }
        val before = 0.9 / 0.03
        val after = peak(loud) / peak(quiet)
        assertTrue(after < before / 3, "loud to quiet went from $before to $after")
        assertTrue(peak(quiet) > 0.03, "quiet parts come up")
        assertTrue(peak(loud) <= 1.0)
    }

    @Test fun clearVoicesLowersWhatIsOnlyAtTheSidesOfAStereoMix() {
        // Same in both channels: the centre, where dialogue sits. Opposite: only at the sides.
        val centre = tone(0.4, 4_800, 2).also { SoundMixer(rate).process(it, 2, AudioMix.VOICES) }
        val sides = tone(0.4, 4_800, 2) { if (it == 0) 1.0 else -1.0 }.also { SoundMixer(rate).process(it, 2, AudioMix.VOICES) }
        assertTrue(peak(centre) > 0.4, "the centre comes up: ${peak(centre)}")
        assertTrue(peak(sides) < 0.4, "the sides go down: ${peak(sides)}")
    }

    @Test fun clearVoicesRaisesTheCentreChannelOfSurroundSound() {
        val surround = tone(0.3, 4_800, 6).also { SoundMixer(rate).process(it, 6, AudioMix.VOICES) }
        val centre = peak(surround, channel = 2, channels = 6)
        val front = peak(surround, channel = 0, channels = 6)
        assertTrue(centre > 0.3 && front < 0.3, "centre $centre, front $front")
    }

    @Test fun monoHasNoCentreToFindAndPassesThroughClearVoices() {
        val input = tone(0.5, 480, 1)
        val output = input.copyOf()
        SoundMixer(rate).process(output, 1, AudioMix.VOICES)
        assertEquals(input.toList(), output.toList())
    }
}
