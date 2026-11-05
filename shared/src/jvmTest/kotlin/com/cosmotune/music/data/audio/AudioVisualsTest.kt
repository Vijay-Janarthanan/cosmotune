package com.cosmotune.music.data.audio

import kotlin.test.*

class AudioVisualsTest {
    @Test fun samplingCannotAlterAudioAndUsesOnlyValidSamples() {
        val samples = floatArrayOf(.5f, -.5f, 100f)
        val before = samples.copyOf()
        val frame = AudioVisuals.sample(samples, 2, .5f)
        assertContentEquals(before, samples)
        assertEquals(128, frame.wave.size)
        assertTrue(frame.wave.all { it == .25f || it == -.25f })
        assertEquals(.25f, frame.energy)
    }

    @Test fun emptyNonfiniteAndOverrangeInputNeverReachesDrawingAsNan() {
        assertEquals(0f, AudioVisuals.sample(floatArrayOf(1f), -1, 1f).energy)
        val frame = AudioVisuals.sample(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 4f, -4f), 100, 1f)
        assertTrue(frame.wave.all { it.isFinite() && it in -1f..1f })
        assertTrue(frame.energy.isFinite())
    }
}
