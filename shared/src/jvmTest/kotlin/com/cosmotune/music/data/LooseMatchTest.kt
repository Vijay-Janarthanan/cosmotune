package com.cosmotune.music.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooseMatchTest {

    @Test
    fun blankMatchesEverything() = assertTrue(LooseMatch.matches("  ", "Anything"))

    @Test
    fun caseAndAccentsAreIgnored() {
        assertTrue(LooseMatch.matches("beyonce halo", "Halo", "Beyoncé"))
        assertTrue(LooseMatch.matches("SIGUR ros", "Hoppípolla", "Sigur Rós"))
    }

    @Test
    fun aLetterWrongInALongerWordStillFinds() {
        assertTrue(LooseMatch.matches("beatels", "Let It Be", "The Beatles"))
        assertTrue(LooseMatch.matches("rihana umbrela", "Umbrella", "Rihanna"))
        assertTrue(LooseMatch.matches("bohemain", "Bohemian Rhapsody", "Queen"))
    }

    @Test
    fun halfTypedWordsMatchTheirStart() {
        assertTrue(LooseMatch.matches("rhaps", "Bohemian Rhapsody", "Queen"))
        assertTrue(LooseMatch.matches("rhapsdy", "Bohemian Rhapsody", "Queen"))
    }

    @Test
    fun shortWordsMustBeAsTypedAndEveryWordCounts() {
        assertFalse(LooseMatch.matches("abc", "Abd"))
        assertFalse(LooseMatch.matches("queen zzzzzz", "Bohemian Rhapsody", "Queen"))
        assertFalse(LooseMatch.matches("metallica", "Bohemian Rhapsody", "Queen"))
    }

    @Test
    fun indicWordsKeepTheirVowelSigns() {
        assertTrue(LooseMatch.matches("கண்ணே", "கண்ணே கலைமானே"))
        assertFalse(LooseMatch.matches("கணண", "கண்ணே கலைமானே"))
    }
}
