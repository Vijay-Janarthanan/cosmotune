package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which lines of a duet are sung from the far side of the page, and how much room they are kept apart by. */
class LyricDuetTest {
    private fun line(alignment: LyricAlignment) = LyricLine(0L, "words", alignment = alignment)

    @Test
    fun aSongThatNamesNoSecondVoiceIsNotADuet() {
        assertFalse(LyricDuet.isDuet(emptyList()))
        assertFalse(LyricDuet.isDuet(listOf(line(LyricAlignment.Start), line(LyricAlignment.Start))))
    }

    @Test
    fun aSingleLineOnTheEndSideMakesTheWholeSongADuet() {
        assertTrue(LyricDuet.isDuet(listOf(line(LyricAlignment.Start), line(LyricAlignment.End), line(LyricAlignment.Start))))
    }

    @Test
    fun onlyInADuetDoesALineSitOnTheEndSide() {
        val end = line(LyricAlignment.End)
        val start = line(LyricAlignment.Start)
        assertTrue(LyricDuet.onEnd(duet = true, line = end))
        assertFalse(LyricDuet.onEnd(duet = true, line = start))
        // a single-voice song has every line on the start side already
        assertFalse(LyricDuet.onEnd(duet = false, line = end))
        assertFalse(LyricDuet.onEnd(duet = false, line = start))
    }

    @Test
    fun theLaneIsKeptClearOnlyInADuet() {
        assertEquals(44f, LyricDuet.laneDp(duet = true), 0f)
        assertEquals(0f, LyricDuet.laneDp(duet = false), 0f)
    }

    @Test
    fun theVerdictIsTheSameForAnyListLength() {
        val long = List(500) { line(LyricAlignment.Start) } + line(LyricAlignment.End)
        assertTrue(LyricDuet.isDuet(long))
    }
}
