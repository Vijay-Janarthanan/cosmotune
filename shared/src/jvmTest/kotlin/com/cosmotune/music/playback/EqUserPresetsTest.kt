package com.cosmotune.music.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqUserPresetsTest {
    private val car = listOf(4f, 2.5f, 0f, -1f, 0f, 2f, 3f)

    @Test fun savedPresetsSurviveTheStore() {
        val saved = EqUserPresets.save(emptyList(), "  In the car\t", car)
        val back = EqUserPresets.decode(EqUserPresets.encode(saved))
        assertEquals(listOf("In the car"), back.map { it.name })
        assertEquals(car, back.single().bands)
    }

    @Test fun theSameNameReplacesInPlaceAndANewOneGoesFirst() {
        var presets = EqUserPresets.save(emptyList(), "Car", car)
        presets = EqUserPresets.save(presets, "Night", List(7) { -1f })
        presets = EqUserPresets.save(presets, "car", List(7) { 1f })
        assertEquals(listOf("Night", "car"), presets.map { it.name })
        assertEquals(1f, presets[1].bands.first())
        assertEquals(listOf("Night"), EqUserPresets.remove(presets, "car").map { it.name })
        assertEquals(presets, EqUserPresets.save(presets, "   ", car))
    }

    @Test fun brokenLinesAreSkippedAndGainsKeptInRange() {
        val back = EqUserPresets.decode("Good\t1,2,3\nno tab here\nBad\t1,x\nLoud\t30,-30")
        assertEquals(listOf("Good", "Loud"), back.map { it.name })
        assertEquals(listOf(12f, -12f), back[1].bands)
        assertTrue(EqUserPresets.decode("").isEmpty())
    }

    @Test fun theChosenPresetIsTheOneTheBandsMatch() {
        val presets = EqUserPresets.save(emptyList(), "Car", car)
        assertEquals("Car", EqUserPresets.matching(presets, car.map { it + 0.01f })?.name)
        assertNull(EqUserPresets.matching(presets, List(7) { 0f }))
    }
}
