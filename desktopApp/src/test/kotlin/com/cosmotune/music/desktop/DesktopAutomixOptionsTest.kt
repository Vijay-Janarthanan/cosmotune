package com.cosmotune.music.desktop

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The desktop Automix phrase-length and skip-mix options, as stored and as handed to the engine. */
class DesktopAutomixOptionsTest {
    @Test fun onlyEightAndSixteenBarsAreFixedLengthsAndEverythingElseIsAdaptive() {
        assertEquals(8, DesktopAutomixOptions.phraseBars("8"))
        assertEquals(16, DesktopAutomixOptions.phraseBars(" 16 "))
        for (stored in listOf("0", "4", "12", "32", "", "eight", "-8")) assertEquals(0, DesktopAutomixOptions.phraseBars(stored), stored)
        assertEquals(listOf(0, 8, 16), DesktopAutomixOptions.PHRASE_CHOICES)
    }

    @Test fun theEngineGetsAFixedLengthOnlyWithAutomixOnAndOutsideAParty() {
        assertEquals(16, DesktopAutomixOptions.enginePhraseBars(automix = true, inParty = false, bars = 16))
        assertEquals(0, DesktopAutomixOptions.enginePhraseBars(automix = false, inParty = false, bars = 16))
        assertEquals(0, DesktopAutomixOptions.enginePhraseBars(automix = true, inParty = true, bars = 8))
        assertEquals(0, DesktopAutomixOptions.enginePhraseBars(automix = true, inParty = false, bars = 12))
    }

    @Test fun aSkipMixesOnlyWhenChosenAndNeverInAParty() {
        assertTrue(DesktopAutomixOptions.skipMix(onSkip = true, inParty = false))
        assertFalse(DesktopAutomixOptions.skipMix(onSkip = true, inParty = true))
        assertFalse(DesktopAutomixOptions.skipMix(onSkip = false, inParty = false))
    }
}
