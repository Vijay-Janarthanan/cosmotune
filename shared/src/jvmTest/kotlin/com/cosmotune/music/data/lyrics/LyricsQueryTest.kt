package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the lyrics sources are asked for: the song's own name, not how a video upload was labelled. */
class LyricsQueryTest {
    @Test fun anUploadTitleWithPipedCreditsAsksForTheSong() {
        assertEquals(
            "Rathinamo",
            "Rathinamo - Official Music Video | Saurav Srisan | Tanisha Gnanavel | Vidhu, Yogalakshmi |TamilIndie".forLyricsSearch(),
        )
        assertEquals("Kurchi Madathapetti", "Kurchi Madathapetti | Full Video Song | Guntur Kaaram".forLyricsSearch())
        assertEquals("Naatu Naatu", "Naatu Naatu - Official Video".forLyricsSearch())
        assertEquals("Hukum", "Hukum – Official Lyric Video".forLyricsSearch())
    }

    @Test fun aRealVersionOrADashInTheNameStays() {
        assertEquals("Song 2 - Remastered 2012", "Song 2 - Remastered 2012".forLyricsSearch())
        assertEquals("Paint It, Black (Live)", "Paint It, Black (Live)".forLyricsSearch())
        assertEquals("Blinding Lights", "Blinding Lights (Official Video)".forLyricsSearch())
        assertEquals("Love Me Like You Do", "Love Me Like You Do (feat. X)".forLyricsSearch())
        assertEquals("|", "|".forLyricsSearch())
    }
}
