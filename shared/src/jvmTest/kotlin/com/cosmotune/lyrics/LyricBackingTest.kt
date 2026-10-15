package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricSyllable
import com.music.bitchord.data.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the second voice under a line is on show, and what it reads as. */
class LyricBackingTest {
    private val timed = LyricLine(
        timeMs = 10_000L,
        text = "(ooh, ooh)",
        words = listOf(LyricWord(10_000L, 10_800L, "(ooh,"), LyricWord(10_900L, 11_600L, "ooh)")),
    )
    private val untimed = LyricLine(timeMs = 10_000L, text = "(ooh, ooh)")

    // ---- when it is open -----------------------------------------------------------------------------------------------

    @Test
    fun aSongWithNoTimingsShowsItAlways() {
        assertTrue(LyricBacking.wanted(timed, synced = false, picking = false, active = false, positionMs = 0L))
        assertTrue(LyricBacking.wanted(untimed, synced = false, picking = false, active = false, positionMs = 99_000L))
    }

    @Test
    fun whileLinesAreBeingPickedItIsOpenSoThatWhatIsOnTheCardCanBeSeen() {
        assertTrue(LyricBacking.wanted(timed, synced = true, picking = true, active = false, positionMs = 0L))
        assertTrue(LyricBacking.wanted(untimed, synced = true, picking = true, active = false, positionMs = 0L))
    }

    @Test
    fun aTimedVoiceOpensJustAheadOfItsFirstWordAndClosesAtItsLast() {
        fun at(position: Long) = LyricBacking.wanted(timed, synced = true, picking = false, active = true, positionMs = position)
        assertFalse(at(9_000L))
        assertFalse(at(9_749L))
        assertTrue(at(9_750L), "250 ms ahead of its first word")
        assertTrue(at(10_500L))
        assertTrue(at(11_599L))
        assertFalse(at(11_600L), "gone at its last word's end")
        assertFalse(at(20_000L))
    }

    @Test
    fun aTimedVoiceKeepsToItsOwnClockNotTheLeads() {
        // the lead line is long over (not active) but the voice under it is still being sung
        assertTrue(LyricBacking.wanted(timed, synced = true, picking = false, active = false, positionMs = 11_000L))
        // and the lead is being sung long before the voice answers
        assertFalse(LyricBacking.wanted(timed, synced = true, picking = false, active = true, positionMs = 8_000L))
    }

    @Test
    fun aVoiceWithNoTimingsOfItsOwnBorrowsTheLeads() {
        assertTrue(LyricBacking.wanted(untimed, synced = true, picking = false, active = true, positionMs = 10_000L))
        assertFalse(LyricBacking.wanted(untimed, synced = true, picking = false, active = false, positionMs = 10_000L))
    }

    // ---- what it reads as ----------------------------------------------------------------------------------------------

    @Test
    fun theBracketsEveryTextOnlySourceWrapsItInAreLeftOff() {
        val shown = LyricBacking.display(timed)
        assertEquals("ooh, ooh", shown.text)
        assertEquals(listOf("ooh,", "ooh"), shown.words.map { it.text })
        assertEquals(10_000L, shown.timeMs)
        assertEquals(listOf(10_000L, 10_900L), shown.words.map { it.startMs }, "its own timings are kept")
    }

    @Test
    fun theWordsStillLineUpWithTheTextAfterTheBracketsAreGone() {
        val shown = LyricBacking.display(timed)
        // the sweep measures the words against the text, so each word must be found in it
        var from = 0
        for (word in shown.words) {
            val at = shown.text.indexOf(word.text, from)
            assertTrue(at >= 0, "'${word.text}' is in '${shown.text}'")
            from = at + word.text.length
        }
    }

    @Test
    fun syllablesAreMovedToKeepCoveringTheSameLetters() {
        val word = LyricWord(
            0L, 1_000L, "(ba-by)",
            syllables = listOf(LyricSyllable(0L, 400L, 0, 4), LyricSyllable(400L, 1_000L, 4, 7)),
        )
        val shown = LyricBacking.display(LyricLine(0L, "(ba-by)", words = listOf(word)))
        assertEquals("ba-by", shown.text)
        val syllables = shown.words.single().syllables
        assertEquals(2, syllables.size)
        assertEquals(0, syllables.first().startChar)
        assertEquals(shown.text.length, syllables.last().endChar)
    }

    @Test
    fun aWordThatWasOnlyBracketsIsDropped() {
        val line = LyricLine(0L, "(ooh) ()", words = listOf(LyricWord(0L, 500L, "(ooh)"), LyricWord(500L, 700L, "()")))
        assertEquals(listOf("ooh"), LyricBacking.display(line).words.map { it.text })
    }

    @Test
    fun aLineWithoutBracketsIsTheSameText() {
        val plain = LyricLine(0L, "echo")
        assertEquals("echo", LyricBacking.display(plain).text)
        assertNull(LyricBacking.display(plain).background)
    }

    // ---- is the translation worth showing under it --------------------------------------------------------------------

    @Test
    fun aTranslationThatIsTheSameWordsIsNotShownTwice() {
        assertFalse(LyricBacking.differs("Falling down", "falling down"))
        assertFalse(LyricBacking.differs("  falling down ", "Falling Down"))
        assertTrue(LyricBacking.differs("cayendo", "falling down"))
    }
}
