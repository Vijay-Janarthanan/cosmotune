package com.cosmotune.ui

import com.cosmotune.lyrics.SungFraction
import com.cosmotune.ui.nowplaying.currentLineAt
import com.music.bitchord.data.lyrics.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** The allocation-free scan the lyrics view runs every frame gives [SungFraction.currentIndex]'s answer, for any list. */
class LyricsCurrentLineTest {
    private fun lines(vararg times: Long) = times.map { LyricLine(it, "line $it") }

    private val songs = listOf(
        lines(),
        lines(0),
        lines(5_000),
        lines(1_000, 4_000, 9_000, 15_000),
        lines(0, 0, 0, 3_000),
        lines(2_000, 9_000, 4_000, 6_000, 12_000),   // out of order: the last stamp that has passed, not the biggest
        lines(3_000, 3_000, 3_000),
    )

    @Test
    fun itAnswersWhatSungFractionAnswersAtEveryPosition() {
        for (song in songs) {
            for (position in -1_000L..16_000L step 250L) {
                assertEquals(SungFraction.currentIndex(song, position), currentLineAt(song, position), "$song at $position")
            }
        }
    }

    @Test
    fun beforeTheFirstLineThereIsNoCurrentLine() = assertEquals(-1, currentLineAt(lines(1_000, 2_000), 999L))

    @Test
    fun aLineIsCurrentFromItsStampOn() {
        val song = lines(1_000, 2_000)
        assertEquals(0, currentLineAt(song, 1_000L))
        assertEquals(0, currentLineAt(song, 1_999L))
        assertEquals(1, currentLineAt(song, 2_000L))
    }
}
