package com.cosmotune.lyrics

import com.music.bitchord.data.lyrics.EnhancedLrc
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.LyricSyllable
import kotlin.test.*

class LrcTimingTest {
    @Test fun positiveOffsetAdvancesLinesAndWordTiming() {
        val line = LrcLib.parseLrc("[offset:+500]\n[00:10.00]<00:10.00>Hello <00:11.00>there<00:12.00>")
            .single { !it.isGap }
        assertEquals(9_500L, line.timeMs)
        assertEquals(listOf(9_500L, 10_500L), line.words.map { it.startMs })
        assertEquals(11_500L, line.words.last().endMs)
    }

    @Test fun negativeOffsetDelaysAndMetadataCanFollowTheLyrics() {
        assertEquals(1_750L, LrcLib.parseLrc("[00:01.00]Words\n[offset:-750]").single().timeMs)
        assertEquals(0L, LrcLib.parseLrc("[offset:2000]\n[00:01.00]Words").single().timeMs)
    }

    @Test fun repeatedChorusTimestampsExpandWithoutAppearingInText() {
        val lines = LrcLib.parseLrc("[00:10.00][00:30.00]Chorus").filterNot { it.isGap }
        assertEquals(listOf(10_000L, 30_000L), lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Chorus"), lines.map { it.text })
    }

    @Test fun repeatedEnhancedChorusShiftsItsWordTimesToo() {
        val lines = LrcLib.parseLrc("[offset:500]\n[00:10.00][00:30.00]<00:10.00>Hello<00:11.00>")
            .filterNot { it.isGap }
        assertEquals(listOf(9_500L, 29_500L), lines.map { it.timeMs })
        assertEquals(listOf(9_500L, 29_500L), lines.map { it.words.single().startMs })
        assertEquals(listOf(10_500L, 30_500L), lines.map { it.words.single().endMs })
    }

    @Test fun wholeSecondAndTenthsStampsRemainTimed() {
        val lines = LrcLib.parseLrc("[00:01]First\n[00:02.5]Second\n[100:00.125]Last")
        assertEquals(listOf(1_000L, 2_500L, 6_000_125L), lines.map { it.timeMs })
    }

    @Test fun enhancedParserAppliesOffsetToLinesWordsAndSyllables() {
        val line = EnhancedLrc.parse("[offset:250]\n[00:10.00]<00:10.00>hel<00:10.50>lo <00:11.00>world")
            .single { !it.isGap }
        assertEquals(9_750L, line.timeMs)
        assertEquals(9_750L, line.words.first().startMs)
        assertEquals(listOf(9_750L, 10_250L), line.words.first().syllables.map { it.startMs })
    }

    @Test fun offsetPreservesAllNestedTimingAndClampsAtZero() {
        val line = LyricLine(100, "a", listOf(LyricWord(100, 400, "a",
            listOf(LyricSyllable(100, 400, 0, 1)))), sungUntilMs = 500,
            background = LyricLine(200, "b"), timingSource = LyricLine(300, "c"))
            .withLrcOffset(250)
        assertEquals(0L, line.timeMs)
        assertEquals(150L, line.words.single().syllables.single().endMs)
        assertEquals(250L, line.sungUntilMs)
        assertEquals(0L, line.background?.timeMs)
        assertEquals(50L, line.timingSource?.timeMs)
    }

    @Test fun invalidOffsetDoesNotDiscardUsableLyrics() {
        assertEquals(1_000L, LrcLib.parseLrc("[offset:invalid]\n[00:01.00]Words").single().timeMs)
        assertEquals(1_000L, LrcLib.parseLrc("[offset:999999999999999999999]\n[00:01.00]Words").single().timeMs)
        assertEquals(Long.MAX_VALUE, LyricLine(1_000, "Words").withLrcOffset(Long.MIN_VALUE).timeMs)
    }
}
