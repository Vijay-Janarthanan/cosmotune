package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How much of the current lyric line is sung, for the fill that follows the singer. */
class SungFractionTest {

    private val lines = listOf(
        LyricLine(1_000, "first line"),
        LyricLine(3_000, "second line"),
        LyricLine(23_000, "third line, after a long slot"),
        LyricLine(40_000, "last line"),
    )

    @Test
    fun beforeTheStampNothingIsSung() {
        assertEquals(0f, SungFraction.of(lines, 0, 500), 1e-6f)
        assertEquals(0f, SungFraction.of(lines, 0, 999), 1e-6f)
    }

    @Test
    fun halfWayToTheNextLinesStampIsHalf() {
        assertEquals(0f, SungFraction.of(lines, 0, 1_000), 1e-6f)
        assertEquals(0.5f, SungFraction.of(lines, 0, 2_000), 1e-4f)
        assertEquals(1f, SungFraction.of(lines, 0, 3_000), 1e-6f)
        assertEquals(1f, SungFraction.of(lines, 0, 9_000), 1e-6f)
    }

    @Test
    fun aTwentySecondSlotIsCappedAtSixSeconds() {
        // The third line has 17 s to the next stamp; it fills over 6 s, so at 3 s it is half.
        assertEquals(0.5f, SungFraction.of(lines, 2, 26_000), 1e-4f)
        assertEquals(1f, SungFraction.of(lines, 2, 29_000), 1e-6f)
    }

    @Test
    fun theLastLineFillsOverFourSeconds() {
        assertEquals(0.5f, SungFraction.of(lines, 3, 42_000), 1e-4f)
        assertEquals(1f, SungFraction.of(lines, 3, 44_000), 1e-6f)
    }

    @Test
    fun aStatedEndIsRespected() {
        val withEnd = listOf(LyricLine(1_000, "line", sungUntilMs = 2_000), LyricLine(9_000, "next"))
        assertEquals(0.5f, SungFraction.of(withEnd, 0, 1_500), 1e-4f)
        assertEquals(1f, SungFraction.of(withEnd, 0, 2_000), 1e-6f)
        // A stated end is not cut short by the 6 s cap: it is the provider's own word.
        val long = listOf(LyricLine(1_000, "line", sungUntilMs = 11_000), LyricLine(20_000, "next"))
        assertEquals(0.5f, SungFraction.of(long, 0, 6_000), 1e-4f)
    }

    @Test
    fun aGapLineIsNeverSung() {
        val withGap = listOf(LyricLine(1_000, "line"), LyricLine(5_000, ""), LyricLine(9_000, "after"))
        assertEquals(0f, SungFraction.of(withGap, 1, 7_000), 0f)
    }

    @Test
    fun aWordSyncedLineIsTheRevealedCharactersOverItsLength() {
        val line = LyricLine(
            timeMs = 1_000,
            text = "hello world",
            words = listOf(LyricWord(1_000, 1_500, "hello"), LyricWord(1_600, 2_000, "world")),
        )
        val synced = listOf(line, LyricLine(4_000, "next"))
        assertEquals(line.revealedChars(1_250) / line.text.length, SungFraction.of(synced, 0, 1_250), 1e-6f)
        assertEquals(2.5f / 11f, SungFraction.of(synced, 0, 1_250), 1e-4f)
        assertEquals(1f, SungFraction.of(synced, 0, 2_500), 1e-6f)
        assertEquals(0f, SungFraction.of(synced, 0, 900), 0f)
    }

    @Test
    fun aTranslationFollowsTheTimingOfTheOriginalVocal() {
        val original = LyricLine(
            timeMs = 1_000,
            text = "hello world",
            words = listOf(LyricWord(1_000, 1_500, "hello"), LyricWord(1_600, 2_000, "world")),
        )
        val translated = LyricLine(1_000, "bonjour le monde", timingSource = original)
        val all = listOf(translated, LyricLine(4_000, "next"))
        assertEquals(2.5f / 11f, SungFraction.of(all, 0, 1_250), 1e-4f)
    }

    @Test
    fun anIndexOutsideTheLyricsIsZero() {
        assertEquals(0f, SungFraction.of(lines, -1, 5_000), 0f)
        assertEquals(0f, SungFraction.of(lines, 99, 5_000), 0f)
        assertEquals(0f, SungFraction.of(emptyList(), 0, 5_000), 0f)
    }

    @Test
    fun theCurrentIndexIsTheLastLineWhoseStampHasPassed() {
        assertEquals(-1, SungFraction.currentIndex(lines, 500))
        assertEquals(0, SungFraction.currentIndex(lines, 1_000))
        assertEquals(0, SungFraction.currentIndex(lines, 2_999))
        assertEquals(1, SungFraction.currentIndex(lines, 3_000))
        assertEquals(3, SungFraction.currentIndex(lines, 600_000))
        assertEquals(-1, SungFraction.currentIndex(emptyList(), 1_000))
    }

    @Test
    fun linesAreSyncedWhenAnyStampIsPositive() {
        assertTrue(SungFraction.isSynced(lines))
        assertFalse(SungFraction.isSynced(listOf(LyricLine(0, "a"), LyricLine(0, "b"))))
        assertFalse(SungFraction.isSynced(emptyList()))
    }
}
