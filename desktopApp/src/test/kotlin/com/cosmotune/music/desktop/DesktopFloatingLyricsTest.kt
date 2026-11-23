package com.cosmotune.music.desktop

import com.cosmotune.music.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFloatingLyricsTest {
    private val lines = listOf(LyricLine(1_000, "First"), LyricLine(4_000, ""), LyricLine(6_000, "Second"), LyricLine(9_000, "Third"))

    @Test fun theSungLineAndTheNextOne() {
        assertEquals(null to "First", DesktopFloatingLyrics.linesAt(lines, 500))
        assertEquals("First" to "Second", DesktopFloatingLyrics.linesAt(lines, 4_500))
        assertEquals("Third" to null, DesktopFloatingLyrics.linesAt(lines, 60_000))
    }

    @Test fun untimedLyricsShowNothing() {
        assertEquals(null to null, DesktopFloatingLyrics.linesAt(listOf(LyricLine(0, "a"), LyricLine(0, "b")), 5_000))
        assertEquals(null to null, DesktopFloatingLyrics.linesAt(emptyList(), 5_000))
    }
}
