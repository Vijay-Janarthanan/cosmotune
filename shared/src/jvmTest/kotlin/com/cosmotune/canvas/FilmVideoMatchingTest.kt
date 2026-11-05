package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.stream.MusicVideoQuery
import com.opencanvas.core.stream.SyncStore
import com.opencanvas.core.stream.SyncOffset
import java.nio.file.Files
import kotlin.test.*

class FilmVideoMatchingTest {
    @Test fun descriptiveSubtitleDoesNotHideThePublisherVideoOrChangeTheRecordingIdentity() {
        val title = "Idhazhin Oram (The Innocence of Love)"
        assertEquals(listOf(title, "Idhazhin Oram"), VideoMatcher.searchTitleVariants(title))
        assertEquals(title, MusicVideoQuery.from(title, "3").title)
        val video = VideoCandidate("lZORMUufA_Y", "3 - Idhazhin Oram Video | Dhanush, Shruti | Anirudh", "SonyMusicSouthVEVO", 196, false, true)
        assertTrue(VideoMatcher.hasPublisherEvidence(video, title, "Anirudh Ravichander", "3"))
        assertFalse(VideoMatcher.hasPublisherEvidence(video.copy(isVerifiedChannel = false, channelTitle = "Fan Music"), title, "Anirudh Ravichander", "3"))
        for (edition in listOf("The Live Acoustic Session", "The Tamil Film Version", "The 2024 New Recording")) {
            val name = "Song ($edition)"
            assertEquals(listOf(name), VideoMatcher.searchTitleVariants(name))
        }
    }

    @Test fun shortNumericMovieNamesMatchOnlyAnEntireToken() {
        assertTrue(VideoMatcher.hasFilmContext("3 - Song Video", "3"))
        assertFalse(VideoMatcher.hasFilmContext("Song 2023 Video", "3"))
        assertFalse(VideoMatcher.hasFilmContext("Song 3D Video", "3"))
        assertFalse(VideoMatcher.hasFilmContext("Song 13 Video", "3"))
        assertFalse(VideoMatcher.hasFilmContext("A Different Film", "A"))
    }
    @Test fun filmCreditSurvivesCatalogueCleanupAndOverridesACompilationAlbum() {
        val query = MusicVideoQuery.from("Nilaave Vaa (From \"Mouna Ragam\")", "Presenting S. P. Balasubrahmanyam (Tamil)")
        assertEquals("Nilaave Vaa", query.title)
        assertEquals("Mouna Ragam", query.album)
        assertEquals(query, MusicVideoQuery.from("Nilaave Vaa (From &quot;Mouna Ragam&quot;)", null))
        assertEquals("Ullasa Paravaigal", MusicVideoQuery.from(
            "Germaniyin Senthen Malare | Ullasa Paravaigal Movie Songs| Kamal Haasan | Rati", null).album)
        assertEquals("From The Start", MusicVideoQuery.from("From The Start (Official Video)", null).title)
    }

    @Test fun aReusedSoundtrackAndAnArtworkUploadDoNotEstablishAnOriginalVideo() {
        val title = "Andhi Mazhai Pozhikaruthu"
        val artists = "S.p. Balasubrahmanyam, S. Janaki"
        assertFalse(VideoMatcher.hasPublisherEvidence(VideoCandidate("jMZTiRcq0AI", "$title - Raaja Paarvai", "Ilaiyaraaja Innisai", 277), title, artists, "Raaja Paarvai"))
        assertFalse(VideoMatcher.hasPublisherEvidence(VideoCandidate("66yaoWv6Cuw", "$title | Raaja Paarvai", "S.Janaki Hits", 282), title, artists, "Raaja Paarvai"))
        // INRECO is a publisher, but the animated artwork must also pass visual-content checks.
        assertTrue(VideoMatcher.hasPublisherEvidence(VideoCandidate("rPp9eSV4H0M", "Germaniyin Senthen Malare | Ullasa Paravaigal Movie Songs", "INRECO Tamil Songs", 339, false, true),
            "Germaniyin Senthen Malare", artists, "Ullasa Paravaigal"))
        assertFalse(VideoMatcher.hasPublisherEvidence(VideoCandidate("GiUoiyF2Rms", "Germaniyin Senthen Malare | 4K Video Song 5.1", "Leo Music Tamil", 309),
            "Germaniyin Senthen Malare", artists, "Ullasa Paravaigal"))
    }

    @Test fun anOldAutomaticSelectionMustBeSearchedAgainUnderTheStricterPolicy() {
        val folder = Files.createTempDirectory("canvas-selection").toFile()
        try {
            folder.resolve("song_old.json").writeText(SyncStore.encode("song", "old", SyncOffset(0, 0.9)).dropLast(1) + ",\"selectionPolicy\":2}")
            val store = SyncStore(folder)
            assertNull(store.verifiedVideoFor("song"))
            store.put("song", "new", SyncOffset(0, 0.9), verifiedSelection = true)
            assertEquals("new", SyncStore(folder).verifiedVideoFor("song"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun aFullFilmDoesNotBecomeASongVideoThroughItsAlbumCredit() {
        val fullMovie = VideoCandidate("CAuf_SKP5bs", "Ullasa Paravaigal Full Movie HD", "PA Art Productions", 6900, false, true)
        assertTrue(VideoMatcher.rank(listOf(fullMovie), "Germaniyin Senthen Malare", "S. P. Balasubrahmanyam", 338, album = "Ullasa Paravaigal").isEmpty())
    }
}
