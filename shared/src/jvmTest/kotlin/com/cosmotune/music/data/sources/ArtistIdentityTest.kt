package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistIdentity
import com.cosmotune.music.data.model.ArtistRef
import kotlin.test.*

class ArtistIdentityTest {
    @Test fun punctuationNamesRemainWholeInSoloAndCollaborativeCredits() {
        assertEquals(listOf("AC/DC"), ArtistIdentity.names("AC/DC"))
        assertEquals(listOf("Tyler, the Creator", "Guest"), ArtistIdentity.names("Tyler, the Creator feat. Guest"))
        assertEquals(listOf("Earth, Wind & Fire", "Guest"), ArtistIdentity.names("Earth, Wind & Fire, Guest"))
        assertEquals(listOf("Cheema Y", "Gur Sidhu"), ArtistIdentity.names("Cheema Y & Gur Sidhu"))
        assertEquals(listOf("ジョン・レノン", "オノ・ヨーコ"), ArtistIdentity.names("ジョン・レノン、オノ・ヨーコ"))
        assertEquals(listOf("A, B & C", "Guest"), ArtistIdentity.names("Unreliable display", listOf(ArtistRef("A, B & C"), ArtistRef("Guest"))))
    }

    @Test fun anyCutIsNeverMadeInsideABandsOwnName() {
        val joiner = Regex("""\s*,\s*|\s+&\s+""")
        assertEquals(listOf("Earth, Wind & Fire", "The Emotions"), ArtistIdentity.split("Earth, Wind & Fire & The Emotions", joiner))
        assertEquals(listOf("Guest", "Mumford & Sons"), ArtistIdentity.split("Guest, Mumford & Sons", joiner))
        assertEquals(listOf("Anitta", "Shakira"), ArtistIdentity.split("Anitta & Shakira", joiner))
        assertEquals(listOf("Solo"), ArtistIdentity.split("  Solo  ", joiner))
        // A protected name inside a longer word is not that band.
        assertEquals(listOf("Years", "Yearsley"), ArtistIdentity.split("Years & Yearsley", joiner))
        assertEquals(listOf(0..17), ArtistIdentity.protectedRanges("Earth, Wind & Fire feat. Guest"))
    }

    @Test fun exactNameExceptionsCannotResolveToOnlyAPartOfTheArtist() {
        assertFalse(TrackMatcher.sharesArtist("Tyler, the Creator", "Tyler"))
        assertFalse(TrackMatcher.sharesArtist("Simon & Garfunkel", "Simon"))
        assertTrue(TrackMatcher.sharesArtist("Tyler, the Creator", "Tyler, the Creator feat. Guest"))
        assertTrue(TrackMatcher.sharesArtist("Atif Aslam", "Atif Aslam, Tulsi Kumar"))
    }
}
