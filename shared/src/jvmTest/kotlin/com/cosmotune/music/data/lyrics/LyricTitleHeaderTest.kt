package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricTitleHeaderTest {
    private val title = "Lantern Season"
    private val artist = "Mira and the Coast"

    private fun lines(vararg texts: String) = texts.mapIndexed { i, t -> LyricLine(i * 1000L, t) }
    private fun strip(vararg texts: String) = LyricTitleHeader.strip(lines(*texts), title, artist).map { it.text }

    @Test fun dropsTheTitleAlone() {
        assertEquals(listOf("we drew a map"), strip("Lantern Season", "we drew a map"))
    }

    @Test fun dropsArtistAndTitle() {
        assertEquals(listOf("we drew a map"), strip("Mira and the Coast - Lantern Season", "we drew a map"))
        assertEquals(listOf("we drew a map"), strip("Lantern Season - Mira and the Coast", "we drew a map"))
    }

    @Test fun dropsTitleWithAVideoHeader() {
        assertEquals(listOf("we drew a map"), strip("Lantern Season (Official Video)", "we drew a map"))
        assertEquals(listOf("we drew a map"), strip("Lantern Season [Lyric Video]", "we drew a map"))
        assertEquals(listOf("we drew a map"), strip("Lantern Season Live at the Harbour - Mira and the Coast", "we drew a map"))
    }

    @Test fun dropsAHeaderOnlyLine() {
        assertEquals(listOf("we drew a map"), strip("(Official Video)", "we drew a map"))
        assertEquals(listOf("we drew a map"), strip("Lyric Video", "we drew a map"))
    }

    @Test fun keepsALineThatSingsTheTitle() {
        val kept = listOf("oh lantern season, stay with me", "we drew a map")
        assertEquals(kept, strip(*kept.toTypedArray()))
    }

    @Test fun onlyLooksAtTheStart() {
        val kept = listOf("we drew a map", "Lantern Season", "hold the line")
        assertEquals(kept, strip(*kept.toTypedArray()))
    }

    @Test fun keepsOrdinaryLinesAndShortTitles() {
        val kept = listOf("every light a place", "hold the line")
        assertEquals(kept, strip(*kept.toTypedArray()))
        assertEquals(kept, LyricTitleHeader.strip(lines(*kept.toTypedArray()), "I", "X").map { it.text })
    }

    @Test fun isHeaderIsNarrow() {
        assertTrue(LyricTitleHeader.isHeader("lantern season", title, artist))
        assertFalse(LyricTitleHeader.isHeader("the lantern season is long", title, artist))
    }
}
