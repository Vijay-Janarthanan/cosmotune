package com.cosmotune.music.playback

import kotlin.test.*

class EqualizerResponseTest {
    @Test fun fullHeadroomIsAnExplicitExtraAttenuationAndDoesNotChangeTheDefaultCurve() {
        val curve = manualCurve(listOf(6f, 3f, 0f, 0f, 0f, 0f, 0f))
        val original = curve.preampDb
        val compensated = curve.withFullHeadroom()
        assertEquals(original, curve.preampDb)
        assertEquals(original * 2f, compensated.preampDb)
        assertTrue(compensated.preampDb < original)
        for (hz in listOf(20f, 60f, 400f, 10_000f, 20_000f))
            assertEquals(original, compensated.responseDb(hz) - curve.responseDb(hz), 0.001f)
        // The existing preamp estimator produces -0.0f for a flat curve; signed
        // zero is the same unity gain and must not be treated as attenuation.
        assertEquals(0f, EqCurve.FLAT.withFullHeadroom().preampDb, 0f)
    }

    @Test fun flatResponseIsZeroAtEverySupportedRateAndNyquistIsRefused() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000)) {
            for (hz in listOf(20f, 400f, rate * 0.49f)) assertEquals(0f, EqCurve.FLAT.responseDb(hz, rate), 0.0001f)
            assertFailsWith<IllegalArgumentException> { EqCurve.FLAT.responseDb(rate / 2f, rate) }
        }
    }
}
