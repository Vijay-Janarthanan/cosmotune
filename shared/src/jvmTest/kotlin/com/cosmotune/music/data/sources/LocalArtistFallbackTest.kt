package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistIdentity
import kotlin.test.*

class LocalArtistFallbackTest {
    @Test fun tagsThenAlbumArtistThenFileNameThenFolderLayout() {
        assertEquals("Ilaiyaraaja", ArtistIdentity.localArtist("Ilaiyaraaja", "Various Artists", "01 Song.flac", listOf("Music", "X", "Y")))
        assertEquals("S. P. Balasubrahmanyam", ArtistIdentity.localArtist("<unknown>", "S. P. Balasubrahmanyam", "01 Song.flac", emptyList()))
        assertEquals("Chitra", ArtistIdentity.localArtist(" ", null, "Chitra - Kannalane.mp3", emptyList()))
        assertEquals("A. R. Rahman", ArtistIdentity.localArtist(null, "Unknown Artist", "03 Track.flac", listOf("home", "me", "Music", "A. R. Rahman", "Roja")))
        // "01 - Title" is a track number, and generic folders never become artists.
        assertNull(ArtistIdentity.localArtist(null, null, "01 - Title.flac", listOf("storage", "emulated", "0", "Music", "Downloads")))
        assertNull(ArtistIdentity.localArtist(null, null, "Title.flac", listOf("Album")))
    }

    @Test fun listenerExceptionsKeepPunctuatedNamesWhole() {
        try {
            assertEquals(listOf("Shankar", "Ehsaan", "Loy"), ArtistIdentity.names("Shankar, Ehsaan & Loy"))
            ArtistIdentity.setUserExceptions(listOf("Shankar, Ehsaan & Loy", " ", "x"))
            assertEquals(listOf("Shankar, Ehsaan & Loy"), ArtistIdentity.userExceptions)
            assertEquals(listOf("Shankar, Ehsaan & Loy", "Shreya Ghoshal"), ArtistIdentity.names("Shankar, Ehsaan & Loy feat. Shreya Ghoshal"))
            assertFalse(TrackMatcher.sharesArtist("Shankar, Ehsaan & Loy", "Loy"))
            assertEquals(listOf("AC/DC"), ArtistIdentity.names("AC/DC")) // built-in exceptions remain
        } finally { ArtistIdentity.setUserExceptions(emptyList()) }
        assertEquals(listOf("Shankar", "Ehsaan", "Loy"), ArtistIdentity.names("Shankar, Ehsaan & Loy"))
    }
}
