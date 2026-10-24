package com.cosmotune.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pipeline readout's bit-exact row: "Yes" only once Android confirms the bit-perfect mixer. */
class BitExactVerdictTest {

    private fun verdict(
        outputExact: Boolean = true,
        bitPerfectConfirmed: Boolean = false,
        directActual: Boolean = false,
        eq: Boolean = false,
        dsd: Boolean = false,
    ) = bitExactVerdict(
        dsd = dsd,
        outputExact = outputExact,
        outputExactDetail = "16-bit",
        loudnessActive = false,
        eqActive = eq,
        spatialActive = false,
        bitPerfectConfirmed = bitPerfectConfirmed,
        directActual = directActual,
    )

    @Test
    fun anExactTrackOnTheMixedPathIsNoLongerCalledBitPerfect() {
        val label = verdict()
        assertFalse(label, label.startsWith("Yes") || label.startsWith("Bit-perfect"))
        assertTrue(label, "Android's mixer" in label)
    }

    @Test
    fun aDirectTrackThatAndroidDidNotConfirmSaysSo() {
        val label = verdict(directActual = true)
        assertTrue(label, "not confirmed" in label)
    }

    @Test
    fun theConfirmedBitPerfectMixerIsTheOnlyWayToTheClaim() {
        assertEquals("Bit-perfect (confirmed by the system) · 16-bit", verdict(bitPerfectConfirmed = true))
        assertEquals("No · equalizer", verdict(bitPerfectConfirmed = true, eq = true))
        assertEquals("No · DSD converted to PCM", verdict(bitPerfectConfirmed = true, dsd = true))
        assertEquals("No · 16-bit", verdict(bitPerfectConfirmed = true, outputExact = false))
    }
}
