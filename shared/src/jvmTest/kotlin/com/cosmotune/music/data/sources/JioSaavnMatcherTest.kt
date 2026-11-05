package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import kotlin.test.*

class JioSaavnMatcherTest {
    private fun row(title: String, artist: String, album: String? = null, duration: String? = null) =
        Song("saavn", title, artist, null, albumName = album, durationText = duration)

    @Test fun albumTitleAndRuntimeWorkWithoutAKnownNameAlias() {
        val target = TrackMatcher.Target("Cheppamma", "Chitra", 289, "Murari (Original Motion Picture Soundtrack)")
        val candidate = row("Cheppamma", "Mani Sharma, K. S. Chithra", "Murari", "4:49")
        assertFalse(TrackMatcher.sharesArtist(target.artist, candidate.artist))
        assertEquals(listOf(candidate), JioSaavnMatcher.ranked(listOf(candidate), target))
        val composerOnly = target.copy(artist = "Completely Different Catalogue Credit")
        assertEquals(listOf(candidate), JioSaavnMatcher.ranked(listOf(candidate), composerOnly))
    }

    @Test fun rangolaSelectsTheOriginalAlbumInsteadOfTheLongerTeluguTitleOrRemix() {
        val target = TrackMatcher.Target("Rangola", "Sankar Mahadevan & Sujatha", 267, "Ghajini")
        val original = row("Rangola", "Sujata, Shankar Mahadevan", "Ghajini", "4:27")
        val telugu = row("Rangola Ola", "Tippu, Sujatha Mohan, Harris Jayaraj", "Ghajini", "4:22").copy(videoId = "telugu")
        val remix = row("Rangola (Remix)", "Shankar Mahadevan, Sujatha Mohan", "Saturday Night", "4:08").copy(videoId = "remix")
        assertEquals(listOf(original), JioSaavnMatcher.ranked(listOf(telugu, remix, original), target))
    }

    @Test fun minorTitleSpellingNeedsTheExactAlbumAndRuntimeNotANameAlias() {
        val target = TrackMatcher.Target("Achcho", "Udit Naryanan & Kavitha Subramaniam", 269, "Shajahaan")
        val original = row("Achacho", "Mani Sharma, Nithyasree Mahadevan", "Shajahaan", "4:29")
        assertEquals(listOf(original), JioSaavnMatcher.ranked(listOf(original), target))
        assertTrue(JioSaavnMatcher.ranked(listOf(original), target.copy(album = null)).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(original), target.copy(durationSec = null)).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(original), target.copy(durationSec = 280)).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(original.copy(title = "Achacho (Remix)")), target).isEmpty())
    }

    @Test fun originalAlbumWinsOverACompilationWithASecondNearerRuntime() {
        val target = TrackMatcher.Target("Kannazhaga (The Kiss of Love)", "Anirudh Ravichander, Dhanush & Shruti Haasan", 206,
            "3 (Original Motion Picture Soundtrack)")
        val album = row("Kannazhaga", "Dhanush, Shruti Haasan, Anirudh Ravichander", "3", "3:25")
        val compilation = album.copy(videoId = "compilation", albumName = "Love Notes", durationText = "3:26")
        assertEquals(album, JioSaavnMatcher.ranked(listOf(compilation, album), target).first())
    }

    @Test fun theCatalogueCanCreditTheComposerOutsideItsPrimaryByline() {
        val names = JioSaavnMatcher.creditNames(listOf("Singer"), listOf("Guest"), listOf(
            "Composer" to "music", "Singer" to "singer", "Actor" to "starring", "Poet" to "lyricist"))
        val candidate = row("A Recording", "Singer", "Film", "4:00").copy(artists = names.map(::ArtistRef))
        assertEquals(listOf(candidate), JioSaavnMatcher.ranked(listOf(candidate), TrackMatcher.Target("A Recording", "Composer", 240)))
        assertTrue(JioSaavnMatcher.ranked(listOf(candidate), TrackMatcher.Target("A Recording", "Actor", 240)).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(candidate), TrackMatcher.Target("A Recording", "Poet", 240)).isEmpty())
        assertEquals("Singer", candidate.artist)
    }

    @Test fun anIdenticalRuntimeDoesNotMakeAnUnrelatedSingerAMatch() {
        val candidate = row("A Recording", "Another Singer", "Film", "4:00")
        assertTrue(JioSaavnMatcher.ranked(listOf(candidate), TrackMatcher.Target("A Recording", "Requested Singer", 240, "Another Film")).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(candidate), TrackMatcher.Target("A Recording", "Unknown Artist", 240)).isEmpty())
    }

    @Test fun searchStartsWithTheFilmAndDoesNotDependOnTheArtistSpelling() {
        val queries = JioSaavnMatcher.queries(TrackMatcher.Target("Naa Manasuney", "S.P.Balasubramanyam & Chitra",
            album = "Manmadhudu (Original Motion Picture Soundtrack)"))
        assertEquals(listOf("naa manasuney manmadhudu", "naa manasuney", "naa manasuney s.p.balasubramanyam"), queries)
        assertEquals(listOf("atუ"), JioSaavnMatcher.queries(TrackMatcher.Target("atუ", "Unknown Artist")))
    }

    @Test fun missingRuntimeDoesNotGuessBetweenDifferentAlbumPerformances() {
        val target = TrackMatcher.Target("A Recording", "Composer", album = "Film")
        val first = row("A Recording", "Vocalist One", "Film", "3:00")
        val second = first.copy(videoId = "second", artist = "Vocalist Two", durationText = "4:00")
        assertTrue(JioSaavnMatcher.ranked(listOf(first, second), target).isEmpty())
        assertTrue(JioSaavnMatcher.ranked(listOf(first, second.copy(durationText = "3:00")), target).isEmpty())
    }

    @Test fun conflictingExplicitAndInstrumentalEditionsAreRejectedEvenOnTheSameAlbum() {
        val target = TrackMatcher.Target("A Recording", "Composer", 240, "Film", isExplicit = true)
        val clean = row("A Recording", "Singer", "Film", "4:00").copy(isExplicit = false)
        val instrumental = clean.copy(isExplicit = true, albumName = "Film (Instrumental Edition)")
        assertTrue(JioSaavnMatcher.ranked(listOf(clean, instrumental), target).isEmpty())
    }

    @Test fun unicodeNamesAndVersionMarkersSurviveTheProviderPolicy() {
        val target = TrackMatcher.Target("Señorita", "Shawn Mendes & Camila Cabello", 190)
        val original = row("Senorita", "Shawn Mendes, Camila Cabello", duration = "3:10")
        assertEquals(listOf(original), JioSaavnMatcher.ranked(listOf(original), target))
        assertTrue(JioSaavnMatcher.ranked(listOf(original.copy(title = "Senorita (Live)")), target).isEmpty())
    }

    @Test fun indianVowelSignsAndJapaneseVoicingAreRecordingIdentity() {
        for ((wanted, different) in listOf("கலை" to "காலை", "कला" to "काला", "か" to "が")) {
            val target = TrackMatcher.Target(wanted, "Artist", 240, "Album")
            val original = row(wanted, "Artist", "Album", "4:00")
            assertEquals(listOf(original), JioSaavnMatcher.ranked(listOf(original), target))
            assertTrue(JioSaavnMatcher.ranked(listOf(original.copy(title = different)), target).isEmpty(), "$wanted / $different")
            assertNotEquals(TrackMatcher.parseTitle(wanted).core, TrackMatcher.parseTitle(different).core)
        }
    }
}
