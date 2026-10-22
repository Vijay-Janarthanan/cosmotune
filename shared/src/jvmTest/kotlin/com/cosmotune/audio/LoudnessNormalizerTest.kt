package com.cosmotune.audio

import kotlin.math.abs
import kotlin.test.*

class LoudnessNormalizerTest {
    @Test fun missingMetadataAndDisabledModeAreBitExact() {
        for ((db, enabled) in listOf(null to true, 9.0 to false, Double.NaN to true)) {
            val samples = floatArrayOf(0.25f, -0.2f, 0.9f, -0.4f)
            val original = samples.copyOf()
            LoudnessNormalizer().process(samples, samples.size, 2, 48000, db, enabled)
            assertContentEquals(original, samples)
        }
    }
    @Test fun loudTracksAreAttenuatedAndStereoBalanceIsPreserved() {
        val samples = floatArrayOf(0.8f, -0.4f, 0.6f, -0.3f)
        LoudnessNormalizer().process(samples, samples.size, 2, 48000, 6.0, true)
        assertTrue(samples[0] in 0.39f..0.41f)
        assertEquals(-2f, samples[0] / samples[1], 0.001f)
    }
    @Test fun boostedTracksCannotClipAndEachTrackKeepsItsOwnGain() {
        val quiet = FloatArray(100) { 0.9f }
        val loud = FloatArray(100) { 0.9f }
        LoudnessNormalizer().process(quiet, quiet.size, 2, 48000, -50.0, true)
        LoudnessNormalizer().process(loud, loud.size, 2, 48000, 10.0, true)
        assertTrue(quiet.all { abs(it) <= 0.98501f })
        assertTrue(loud.all { it < 0.3f })
    }
}
