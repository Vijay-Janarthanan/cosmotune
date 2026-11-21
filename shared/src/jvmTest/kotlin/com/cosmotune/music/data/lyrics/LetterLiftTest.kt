package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sung word's letters rise one after another, in scripts whose letters stand apart, and come down as one. */
class LetterLiftTest {
    private val line = LyricLine(timeMs = 1_000, text = "around", words = listOf(LyricWord(1_000, 1_600, "around")))

    @Test
    fun theLettersRiseInTurnAndTheLastStartsAt65Percent() {
        // 600 ms of rise: the last of six letters starts at 0.65 × 600 = 390 ms in.
        assertEquals(0f, line.letterLift(0, 5, 6, 1_390))
        assertTrue(line.letterLift(0, 5, 6, 1_400) > 0f)
        val mid = (0 until 6).map { line.letterLift(0, it, 6, 1_300) }
        assertEquals(mid.sortedDescending(), mid, "earlier letters are higher while the word rises")
        // The first letter is the whole word's lift as it was, so a word that is not cut looks the same as before.
        for (t in 1_000L..2_400L step 50) assertEquals(line.wordLift(0, t), line.letterLift(0, 0, 6, t), 0.0001f)
    }

    @Test
    fun theWordComesDownAsOne() {
        // At the word's end every letter is at the top, and on the way down they stay level.
        val top = line.letterLift(0, 0, 6, 1_600)
        for (k in 0 until 6) assertEquals(top, line.letterLift(0, k, 6, 1_600), 0.0001f)
        for (k in 0 until 6) assertEquals(line.wordLift(0, 1_900), line.letterLift(0, k, 6, 1_900), 0.0001f)
    }

    @Test
    fun lettersStandingApartAreCutAndAccentsStayWithTheirLetter() {
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6), LetterLift.cuts("around"))
        // "e" with a combining acute is one letter of two chars.
        assertContentEquals(intArrayOf(0, 1, 2, 3, 5), LetterLift.cuts("cafe\u0301"))
        assertContentEquals(intArrayOf(0, 1, 2), LetterLift.cuts("日本"))
    }

    @Test
    fun joinedScriptsAndSingleLettersLiftAsOne() {
        assertNull(LetterLift.cuts("கண்ணே"), "Tamil")
        assertNull(LetterLift.cuts("नमस्ते"), "Hindi")
        assertNull(LetterLift.cuts("حب"), "Arabic")
        assertNull(LetterLift.cuts("a"))
        assertNull(LetterLift.cuts("e\u0301"), "one letter with its accent")
    }
}
