package com.cosmotune.music.data.lyrics

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LrcExportTest {
    private val song = Song("v", "Kannalane", "A. R. Rahman, K. S. Chithra", null, albumName = "Bombay [OST]", durationText = "5:35")

    @Test fun timedLyricsRoundTripThroughTheLrcReaders() {
        val lines = listOf(
            LyricLine(12_340, "Kannalane enadhu", background = LyricLine(13_000, "ooh")),
            LyricLine(65_000, ""),
            LyricLine(70_500, "Kangalai kaanen"),
        )
        val lrc = LrcExport.render(song, lines)
        assertTrue(lrc.startsWith("[ti:Kannalane]\n[ar:A. R. Rahman, K. S. Chithra]\n[al:Bombay  OST]\n[length:05:35]\n[by:Cosmotune]\n"))
        assertTrue("[00:12.34]Kannalane enadhu\n[00:13.00](ooh)\n[01:05.00]\n[01:10.50]Kangalai kaanen\n" in lrc)
        val parsed = LrcLib.parseLrc(lrc).filter { it.text.isNotBlank() }
        assertEquals(listOf(12_340L, 13_000L, 70_500L), parsed.map { it.timeMs })
        assertEquals(listOf("Kannalane enadhu", "(ooh)", "Kangalai kaanen"), parsed.map { it.text })
    }

    @Test fun wordTimingIsWrittenAsEnhancedLrc() {
        val words = listOf(LyricWord(1_000, 1_400, "Hello"), LyricWord(1_500, 2_200, "world"))
        val lrc = LrcExport.render(song, listOf(LyricLine(1_000, "Hello world", words = words), LyricLine(4_000, "Next")))
        assertTrue("[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.20>\n" in lrc, lrc)
        val parsed = EnhancedLrc.parse(lrc)
        assertEquals(listOf("Hello", "world"), parsed.first().words.map { it.text.trim() })
        assertEquals(listOf(1_000L, 1_500L), parsed.first().words.map { it.startMs })
        // Players without word support still read the whole line.
        assertEquals("Hello world", LrcLib.parseLrc(lrc).first().text.replace(Regex("<[^>]*>"), "").trim())
    }

    @Test fun plainLyricsStayPlainAndEmptyLyricsAreRefused() {
        val lrc = LrcExport.render(song, listOf(LyricLine(0, "First line"), LyricLine(0, ""), LyricLine(0, "Second line")))
        assertTrue(lrc.endsWith("\nFirst line\nSecond line\n"))
        assertFailsWith<IllegalArgumentException> { LrcExport.render(song, listOf(LyricLine(5_000, ""))) }
        assertEquals("A. R. Rahman, K. S. Chithra - Kannalane.lrc", LrcExport.fileName(song))
        assertEquals("AC DC - Back in Black.lrc", LrcExport.fileName(Song("x", "Back in Black?", "AC/DC", null)))
        assertEquals("61:01.50", LrcExport.stamp(3_661_500))
    }
}
