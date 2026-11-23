package com.cosmotune.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundEffectsDspTest {

    private val rate = 48_000

    private fun tone(seconds: Float, hz: Float = 440f, left: Float = 0.5f, right: Float = 0.5f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i ->
            val v = sin(2 * PI * hz * (i / 2) / rate).toFloat()
            if (i % 2 == 0) v * left else v * right
        }
    }

    private fun energy(samples: FloatArray, from: Int, to: Int, channel: Int): Double {
        var sum = 0.0
        var i = from * 2 + channel
        while (i < to * 2) { sum += samples[i] * samples[i]; i += 2 }
        return sum
    }

    @Test
    fun noEffectAndNoBoostLeavesTheBlockUntouched() {
        val input = tone(0.2f)
        val out = input.copyOf()
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.NONE, 1f)
        assertContentEquals(input, out)
    }

    @Test
    fun eightDMovesTheSongBetweenTheEars() {
        val out = tone(8f)
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.EIGHT_D, 1f)
        // A quarter of the way round it leans right, three quarters round it leans left.
        val quarter = rate * 2
        val window = rate / 4
        val rightFirst = energy(out, quarter - window, quarter + window, 1) > 2 * energy(out, quarter - window, quarter + window, 0)
        val leftLater = energy(out, 3 * quarter - window, 3 * quarter + window, 0) > 2 * energy(out, 3 * quarter - window, 3 * quarter + window, 1)
        assertTrue(rightFirst && leftLater)
    }

    @Test
    fun vocalRemoverTakesOutWhatIsInTheMiddle() {
        val out = tone(1f, hz = 1_000f)
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.VOCAL_REMOVER, 1f)
        val before = energy(tone(1f, hz = 1_000f), rate / 2, rate, 0)
        val after = energy(out, rate / 2, rate, 0)
        assertTrue(after < before * 0.05, "centred tone should mostly vanish: $after vs $before")
    }

    @Test
    fun echoBringsASoundBackLater() {
        val frames = rate
        val out = FloatArray(frames * 2)
        for (i in 0 until 480 * 2) out[i] = 0.5f
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.ECHO, 1f)
        val tail = energy(out, (rate * 0.33f).toInt(), (rate * 0.4f).toInt(), 0) + energy(out, (rate * 0.33f).toInt(), (rate * 0.4f).toInt(), 1)
        assertTrue(tail > 0.1)
    }

    @Test
    fun reverbLeavesATailAfterTheSoundStops() {
        val out = FloatArray(rate * 2)
        for (i in 0 until 4_800 * 2) out[i] = if ((i / 2) % 100 < 50) 0.5f else -0.5f
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.HALL, 1f)
        assertTrue(energy(out, rate / 2, rate * 3 / 4, 0) > 1e-4)
    }

    @Test
    fun boostIsLouderButNeverPastFullScale() {
        val out = tone(1f, left = 0.9f, right = 0.9f)
        SoundEffectsDsp(rate).process(out, out.size, SoundEffect.NONE, 1f, boostGain = 2f)
        assertTrue(out.all { abs(it) <= 1f })
        val quiet = tone(1f, left = 0.1f, right = 0.1f)
        SoundEffectsDsp(rate).process(quiet, quiet.size, SoundEffect.NONE, 1f, boostGain = 2f)
        val peak = (rate / 2 until rate).maxOf { abs(quiet[it * 2]) }
        assertTrue(peak > 0.18f, "boost should nearly double a quiet tone, got $peak")
    }

    @Test
    fun namesRoundTripAndUnknownIsNone() {
        SoundEffect.entries.forEach { assertEquals(it, SoundEffect.named(it.name)) }
        assertEquals(SoundEffect.NONE, SoundEffect.named("SPARKLE"))
        assertEquals(SoundEffect.NONE, SoundEffect.named(null))
    }
}
