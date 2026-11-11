package com.cosmotune.audio

import kotlin.test.*

class HeadphoneEqTest {
    @Test fun catalogueRetainsAllModelsAndMeasurementAuthors() {
        assertEquals(39, HeadphoneEq.presets.size)
        assertTrue(HeadphoneEq.presets.all { it.author.isNotBlank() && "Filter 1:" in it.text })
    }
    @Test fun graphicAndParametricInputsArePreviewedWithHeadroomAndBoundedValues() {
        val (flat, provenance) = HeadphoneEq.importCurve("GraphicEQ: 20 -2; 20000 -2", "My headphone", "https://example.com/measurement", "User supplied")
        assertTrue(flat.bands.all { it == -2f }); assertTrue(flat.fullHeadroom)
        assertEquals("My headphone", provenance.headphone)
        val (peq, _) = HeadphoneEq.importCurve("Filter 1: ON PK Fc 1000 Hz Gain -4 dB Q 1", "My headphone", "https://example.com/measurement", "User supplied")
        assertEquals(-4f, peq.bands[3], .001f)
        assertTrue(peq.bands.first() > peq.bands[3])
        assertFailsWith<IllegalArgumentException> { HeadphoneEq.importCurve("Filter 1: ON PK Fc 1000 Hz Gain NaN dB Q 1", "My headphone", "https://example.com", "Rig") }
        assertFailsWith<IllegalArgumentException> { HeadphoneEq.importCurve("GraphicEQ: 1000 0; 20 0", "My headphone", "https://example.com", "Rig") }
    }
}
