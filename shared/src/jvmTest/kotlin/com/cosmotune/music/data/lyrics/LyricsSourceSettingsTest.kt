package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** Saved choices from older builds, which named sources this one no longer has. */
class LyricsSourceSettingsTest {

    @Test
    fun `the list is the five measured sources, word-timed first`() {
        assertEquals(
            listOf(
                LyricsSource.LRC_RED,
                LyricsSource.BETTER_LYRICS,
                LyricsSource.BETTER_LYRICS_PORTATO,
                LyricsSource.YOUTUBE_MUSIC,
                LyricsSource.LRCLIB,
            ),
            LyricsSource.offered,
        )
    }

    @Test
    fun `a saved choice keeps what survives and drops what was removed`() {
        val chosen = listOf("GENIUS", "LRCLIB", "PAXSENIX_SPOTIFY", "YOUTUBE_MUSIC")
        val seen = LyricsSource.offered.map { it.name } + listOf("GENIUS", "PAXSENIX_SPOTIFY", "KUGOU")
        assertEquals(setOf(LyricsSource.LRCLIB, LyricsSource.YOUTUBE_MUSIC), LyricsSource.enabled(chosen, seen))
    }

    @Test
    fun `a choice that named only removed sources starts over with every source`() {
        val chosen = listOf("GENIUS", "MEGALOBIZ")
        val seen = LyricsSource.offered.map { it.name } + chosen
        assertEquals(LyricsSource.offered.toSet(), LyricsSource.enabled(chosen, seen))
    }

    @Test
    fun `a source never shown when the choice was made is switched on`() {
        val seen = listOf("LRC_RED", "LRCLIB", "GENIUS")
        assertEquals(
            setOf(LyricsSource.LRCLIB, LyricsSource.BETTER_LYRICS, LyricsSource.BETTER_LYRICS_PORTATO, LyricsSource.YOUTUBE_MUSIC),
            LyricsSource.enabled(listOf("LRCLIB"), seen),
        )
    }

    @Test
    fun `a saved order with removed names keeps the listener's order for the rest`() {
        // What an old build saved, read back the way the settings read it: unknown names fall out first.
        val saved = listOf("GENIUS", "LRCLIB", "KUGOU", "YOUTUBE_MUSIC", "BINI_LYRICS", "LRC_RED")
            .mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }
        val ordered = LyricsSource.ordered(saved)
        assertEquals(LyricsSource.offered.toSet(), ordered.toSet())
        assertEquals(
            listOf(LyricsSource.LRCLIB, LyricsSource.YOUTUBE_MUSIC, LyricsSource.LRC_RED),
            ordered.filter { it in saved },
        )
    }
}
