package com.cosmotune.music.data.canvas

import com.cosmotune.design.TitleArt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The genres the catalogues have named, kept for the title art's voice. */
class AlbumGenresTest {

    @AfterTest fun forget() = AlbumGenres.clear()

    @Test fun appleGenresAreKeptWithoutTheCatchAll() {
        AlbumGenres.record("eternal sunshine", "Ariana Grande", listOf("Pop", "Music"))
        assertEquals("Pop", AlbumGenres.genreFor("Eternal Sunshine", "Ariana Grande"))
        assertEquals("Soundtrack / Tamil / Indian", AlbumGenres.genreOf(listOf("Soundtrack", "Music", "Tamil", "Indian", "tamil")))
        assertNull(AlbumGenres.genreOf(listOf("Music")))
    }

    @Test fun theLeadArtistKeysIt() {
        AlbumGenres.record("Vinnaithaandi Varuvaayaa", "A. R. Rahman, Karthik", listOf("Soundtrack", "Tamil"))
        assertEquals("Soundtrack / Tamil", AlbumGenres.genreFor("Vinnaithaandi Varuvaayaa", "A. R. Rahman & Shreya Ghoshal"))
        assertNull(AlbumGenres.genreFor("Vinnaithaandi Varuvaayaa", "Someone Else"), "another artist's namesake is not this")
        assertEquals("Soundtrack / Tamil", AlbumGenres.genreFor("Vinnaithaandi Varuvaayaa", null), "a name alone, with no artist to ask")
    }

    @Test fun namesInAnyScriptMatch() {
        AlbumGenres.record("விண்ணைத்தாண்டி வருவாயா", "ஏ. ஆர். ரஹ்மான்", listOf("Tamil"))
        assertEquals("Tamil", AlbumGenres.genreFor("விண்ணைத்தாண்டி  வருவாயா!", "ஏ. ஆர். ரஹ்மான்"))
    }

    @Test fun anAnswerMovesTheVersionOn() {
        val before = AlbumGenres.version.value
        AlbumGenres.record("GNX", "Kendrick Lamar", listOf("Hip-Hop/Rap", "Music"))
        assertTrue(AlbumGenres.version.value > before)
        val after = AlbumGenres.version.value
        AlbumGenres.record("GNX", "Kendrick Lamar", listOf("Hip-Hop/Rap", "Music"))
        assertEquals(after, AlbumGenres.version.value, "the same answer again changes nothing")
    }

    @Test fun whatIsKeptGivesAVoice() {
        AlbumGenres.record("GNX", "Kendrick Lamar", listOf("Hip-Hop/Rap", "Music"))
        assertEquals(TitleArt.Voice.CONDENSED, TitleArt.voice(AlbumGenres.genreFor("GNX", "Kendrick Lamar"), 0.5f))
        AlbumGenres.record("Kannalane", "A. R. Rahman", listOf("Tamil"))
        assertEquals(TitleArt.Voice.SERIF, TitleArt.voice(AlbumGenres.genreFor("Kannalane", "A. R. Rahman"), 0.7f, 0.2f))
    }
}
