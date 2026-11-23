package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricWidgetStateTest {
    private val lines = listOf(LyricLine(1_000, "First line"), LyricLine(4_000, ""), LyricLine(6_000, "Second line"), LyricLine(9_000, "Last line"))

    @Test fun theSungLineIsShownWithTheNextOneUnderIt() {
        assertEquals(LyricWidgetState("Song · Singer", "First line", "Second line"), LyricWidgetState.at("Song", "Singer", lines, 2_000))
        assertEquals(LyricWidgetState("Song · Singer", "Last line", ""), LyricWidgetState.at("Song", "Singer", lines, 60_000))
    }

    @Test fun beforeTheFirstLineAndInAGapTheComingLineWaits() {
        assertEquals(LyricWidgetState("Song · Singer", "♪", "First line"), LyricWidgetState.at("Song", "Singer", lines, 0))
        assertEquals(LyricWidgetState("Song · Singer", "♪", "Second line"), LyricWidgetState.at("Song", "Singer", lines, 5_000))
    }

    @Test fun noLyricsOrUntimedLyricsSaySo() {
        assertEquals("No lyrics for this song", LyricWidgetState.at("Song", "", null, 0).line)
        assertEquals("Song", LyricWidgetState.at("Song", "", null, 0).song)
        assertEquals("These lyrics are not timed", LyricWidgetState.at("Song", "Singer", listOf(LyricLine(0, "a"), LyricLine(0, "b")), 9_000).line)
    }
}
