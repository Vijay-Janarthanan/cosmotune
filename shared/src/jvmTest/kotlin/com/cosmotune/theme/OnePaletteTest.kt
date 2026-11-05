package com.cosmotune.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The One palette (DESIGN_SYSTEM.md 2.1, 2.2, 2.4): the spec's values, and every rail the cover palettes hold. */
class OnePaletteTest : ThemeTestBase() {
    @Test fun theNeutralRolesAreTheSpecs() {
        val dark = OnePalette.of(dark = true)
        assertEquals("#0f0f11", hex(dark.backgroundBottom))
        assertEquals("#17171a", hex(dark.surface)); assertEquals("#1f1f23", hex(dark.surfaceHigh)); assertEquals("#29292e", hex(dark.surfaceVariant))
        assertEquals("#ffb98f", hex(dark.accent))
        assertEquals("#000000", hex(OnePalette.of(dark = true, amoled = true).backgroundBottom))
        val light = OnePalette.of(dark = false)
        assertEquals("#f7f7f8", hex(light.backgroundBottom)); assertEquals("#a24b17", hex(light.accent))
        assertEquals(light, OnePalette.of(dark = false, amoled = true), "the light theme ignores AMOLED")
    }

    @Test fun everyRailHolds() {
        assertRails("dark", OnePalette.of(dark = true), dark = true)
        assertRails("amoled", OnePalette.of(dark = true, amoled = true), dark = true)
        assertRails("light", OnePalette.of(dark = false), dark = false)
    }

    @Test fun theTiersStepUpFromThePage() {
        for (amoled in listOf(false, true)) {
            val p = OnePalette.of(dark = true, amoled = amoled)
            val steps = listOf(p.backgroundBottom, p.surface, p.surfaceHigh, p.surfaceVariant).map { luminance(it) }
            assertEquals(steps.sorted(), steps)
            assertTrue(steps.toSet().size == 4, "four distinct tiers")
        }
    }
}
