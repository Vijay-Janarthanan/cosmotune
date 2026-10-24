package com.cosmotune.music.desktop

import com.cosmotune.audio.OutputFidelity
import com.cosmotune.audio.OutputFidelityVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The desktop pipeline readout. Every desktop output is shared today, so it may say "unchanged
 * by Cosmotune" but never "bit-perfect". Pure: no registry, no audio device.
 */
class DesktopOutputFidelityTest {

    private val clean = DesktopAudioPipeline(
        sourceFormat = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16, channels = 2),
        decodedSampleRateHz = 44_100,
        outputSampleRateHz = 44_100,
        outputIsFloat = true,
        outputBytesPerSample = 4,
        outputApi = "WASAPI shared",
        systemName = "Windows",
    )

    @Test
    fun `an untouched stream through WASAPI shared mode is never called bit-perfect`() {
        val verdict = OutputFidelityVerdict.of(clean.fidelityFacts())
        assertIs<OutputFidelity.UnchangedByApp>(verdict)
        val label = OutputFidelityVerdict.label(verdict)
        assertFalse("perfect" in label.lowercase(), label)
        assertTrue("Windows's mixer" in label, label)
    }

    @Test
    fun `each stage inside Cosmotune that changes samples is named`() {
        assertEquals(OutputFidelity.Altered("loudness normalization"), OutputFidelityVerdict.of(clean.copy(loudnessActive = true).fidelityFacts()))
        assertEquals(OutputFidelity.Altered("equalizer"), OutputFidelityVerdict.of(clean.copy(equalizerEnabled = true).fidelityFacts()))
        assertEquals(OutputFidelity.Altered("volume below 100%"), OutputFidelityVerdict.of(clean.copy(volumeBelowFull = true).fidelityFacts()))
        assertEquals(OutputFidelity.Altered("resampled"), OutputFidelityVerdict.of(clean.copy(outputSampleRateHz = 48_000).fidelityFacts()))
    }

    @Test
    fun `a 24-bit file on a 16-bit Java Sound fallback loses bits, a float output does not`() {
        val hiRes = clean.copy(sourceFormat = clean.sourceFormat!!.copy(bitDepth = 24))
        assertIs<OutputFidelity.UnchangedByApp>(OutputFidelityVerdict.of(hiRes.fidelityFacts()))
        val narrowed = hiRes.copy(outputIsFloat = false, outputBytesPerSample = 2, outputApi = "Java Sound")
        assertEquals(
            OutputFidelity.Altered("24-bit source written as 16-bit"),
            OutputFidelityVerdict.of(narrowed.fidelityFacts()),
        )
    }

    @Test
    fun `a DSD file is reported as converted to PCM`() {
        val dsd = clean.copy(sourceFormat = clean.sourceFormat!!.copy(codec = "audio/dsf"))
        assertEquals(OutputFidelity.Altered("DSD converted to PCM"), OutputFidelityVerdict.of(dsd.fidelityFacts()))
    }
}
