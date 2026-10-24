package com.cosmotune.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The output readout may only say "bit-perfect" when the platform confirmed it. */
class OutputFidelityTest {

    private val clean = OutputFidelityFacts(encodingExact = true, systemName = "Android")

    @Test
    fun `an untouched stream on the system mixer is unchanged by the app, never bit-perfect`() {
        val verdict = OutputFidelityVerdict.of(clean)
        assertIs<OutputFidelity.UnchangedByApp>(verdict)
        val label = OutputFidelityVerdict.label(verdict)
        assertFalse("perfect" in label.lowercase(), label)
        assertTrue("mixer" in label, label)
    }

    @Test
    fun `a direct path the platform did not confirm is still not bit-perfect`() {
        val label = OutputFidelityVerdict.label(clean.copy(mixing = OutputMixing.DIRECT_UNCONFIRMED))
        assertFalse(label.startsWith("Bit-perfect"), label)
        assertTrue("not confirmed" in label, label)
    }

    @Test
    fun `only a platform-confirmed path with nothing altering reads bit-perfect`() {
        val verdict = OutputFidelityVerdict.of(
            clean.copy(mixing = OutputMixing.BIT_PERFECT_CONFIRMED, encodingDetail = "16-bit / 44.1 kHz"),
        )
        assertEquals(OutputFidelity.BitPerfect("16-bit / 44.1 kHz"), verdict)
        assertTrue(OutputFidelityVerdict.label(verdict).startsWith("Bit-perfect (confirmed"))
    }

    @Test
    fun `a confirmed path loses the claim to anything inside the app that changes samples`() {
        val confirmed = clean.copy(mixing = OutputMixing.BIT_PERFECT_CONFIRMED)
        assertEquals(OutputFidelity.Altered("equalizer"), OutputFidelityVerdict.of(confirmed.copy(alteringStage = "equalizer")))
        assertEquals(OutputFidelity.Altered("resampled"), OutputFidelityVerdict.of(confirmed.copy(resampledByApp = true)))
        assertEquals(
            OutputFidelity.Altered("24-bit source written as 16-bit"),
            OutputFidelityVerdict.of(confirmed.copy(encodingExact = false, encodingDetail = "24-bit source written as 16-bit")),
        )
        assertEquals(OutputFidelity.Altered("converted downstream"), OutputFidelityVerdict.of(confirmed.copy(encodingExact = false)))
    }

    @Test
    fun `DSD is named first, ahead of any stage after the conversion`() {
        val verdict = OutputFidelityVerdict.of(
            clean.copy(dsdConvertedToPcm = true, alteringStage = "equalizer", mixing = OutputMixing.BIT_PERFECT_CONFIRMED),
        )
        assertEquals(OutputFidelity.Altered("DSD converted to PCM"), verdict)
        assertEquals("No · DSD converted to PCM", OutputFidelityVerdict.label(verdict))
    }
}
