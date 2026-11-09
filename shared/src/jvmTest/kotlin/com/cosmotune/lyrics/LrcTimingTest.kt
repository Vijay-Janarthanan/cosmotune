package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.EnhancedLrc
import com.cosmotune.music.data.lyrics.KaraokeLrc
import com.cosmotune.music.data.lyrics.LrcLib
import com.cosmotune.music.data.lyrics.LyricAlignment
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricWord
import com.cosmotune.music.data.lyrics.LyricSyllable
import kotlin.test.*

class LrcTimingTest {
    @Test fun leadingVoiceLabelsBecomeDuetAlignmentWithoutInventingTiming() {
        val input = "[00:01.200]v1:First voice\n[00:02.400]V02 ： Second voice\n[00:03.600]A literal v1: mention"
        val lines = LrcLib.parseLrc(input).filterNot { it.isGap }
        assertEquals(listOf("First voice", "Second voice", "A literal v1: mention"), lines.map { it.text })
        assertEquals(listOf(1_200L, 2_400L, 3_600L), lines.map { it.timeMs })
        assertEquals(listOf(LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start), lines.map { it.alignment })
        assertTrue(lines.all { it.words.isEmpty() })

        val plain = assertNotNull(LrcLib.parseEmbedded("v1:First voice\nv2:Second voice"))
        assertEquals(lines.take(2).map { it.text }, plain.map { it.text })
        assertEquals(lines.take(2).map { it.alignment }, plain.map { it.alignment })
        assertTrue(plain.all { it.timeMs == 0L && it.words.isEmpty() })
    }

    @Test fun markerOnlyTimedRunsDisappearAndLineOnlyRolesKeepExistingWordTiming() {
        val input = "[offset:100]\n[00:01.00]v1:<00:01.20>First<00:01.80>\n" +
            "[00:02.00]<00:02.00>v2<00:02.10>： <00:02.40>Hello <00:03.00>world<00:03.80>"
        for (parse in listOf(LrcLib::parseLrc, EnhancedLrc::parse)) {
            val lines = parse(input).filterNot { it.isGap }
            assertEquals(listOf("First", "Hello world"), lines.map { it.text })
            assertEquals(listOf(900L, 1_900L), lines.map { it.timeMs })
            assertEquals(listOf(LyricAlignment.Start, LyricAlignment.End), lines.map { it.alignment })
            assertEquals(listOf(2_300L, 2_900L), lines.last().words.map { it.startMs })
            assertEquals(3_700L, lines.last().words.last().endMs)
        }
    }

    @Test fun karaokeRoleDoesNotConsumeTheFirstSungWordOrItsTiming() {
        val input = "[1000,2400](1000,100,0)v1:(1100,800,0)Hello (1900,1200,0)world\n" +
            "[3500,1800]v2:(3500,900,0)Another (4400,900,0)voice"
        val direct = KaraokeLrc.parse(input).filterNot { it.isGap }
        val embedded = assertNotNull(LrcLib.parseEmbedded(input)).filterNot { it.isGap }
        assertEquals(direct, embedded)
        assertEquals(listOf("Hello world", "Another voice"), direct.map { it.text })
        assertEquals(LyricAlignment.End, direct.last().alignment)
        assertEquals(listOf(1_100L, 1_900L), direct.first().words.map { it.startMs })
        assertEquals(3_400L, direct.first().sungUntilMs)
    }

    @Test fun aSeparatedBackingRowAttachesByOnsetAndKeepsItsOwnSweep() {
        val input = "[offset:100]\n[bg:<00:02.4>v2: <00:02.5>Echo <00:03.2>back<00:03.8>]\n" +
            "[00:01]<00:01>Lead<00:02>\n[00:03]<00:03>Next<00:04>"
        for (parse in listOf(LrcLib::parseLrc, EnhancedLrc::parse)) {
            val lines = parse(input).filterNot { it.isGap }
            val backing = assertNotNull(lines.first().background)
            assertEquals("Echo back", backing.text)
            assertEquals(2_300L, backing.timeMs)
            assertEquals(listOf(2_400L, 3_100L), backing.words.map { it.startMs })
            assertEquals(3_700L, backing.words.last().endMs)
            assertNull(lines.last().background)
        }
    }

    @Test fun supplementaryUnicodeEntitiesStayIntactInNativeLyrics() {
        assertEquals("🎵 🎵 don't", EnhancedLrc.decodeEntities("&#x1F3B5; &#127925; don&#39;t"))
        val sung = EnhancedLrc.parse("[00:01.00]<00:01.00>&#x1F3B5; <00:02.00>music")
            .single { !it.isGap }
        assertEquals("🎵 music", sung.text)
    }

    @Test fun invalidEntitiesCannotAbortTheWholeLyricsLookup() {
        val invalid = "&#x110000; &#9999999999999999999999999; &#xD800;"
        assertEquals(invalid, EnhancedLrc.decodeEntities(invalid))
        assertEquals("&#x27;", EnhancedLrc.decodeEntities("&amp;#x27;"), "only decode one layer")
        assertEquals(invalid, EnhancedLrc.parse("[00:01.00]<00:01.00>$invalid").single { !it.isGap }.text)
    }

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
