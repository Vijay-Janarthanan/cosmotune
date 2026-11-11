package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.stream.MusicVideoSearch
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recorded searches for "Hangova" (Anirudh Ravichander, Heisenberg, from the film DC) and its neighbours on the album. */
class FilmSongVideoEligibilityTest {
    private val artist = "Anirudh Ravichander, Heisenberg"

    @Test fun aTvChannelsMusicVideoOfTheSongIsEligibleUnderATwoLetterFilm() {
        val sunTv = RecordedVideoSearch.candidate("Hangova DC video song", "wc8XNJ2x1Xs")
        assertEquals("Sun TV", sunTv.channelTitle)
        assertTrue(VideoMatcher.hasFilmContext(sunTv.title, "DC"), "\"| DC |\" names the film")
        assertFalse(VideoMatcher.hasFilmContext("Hangova - Music Video | Abcdcxyz", "DC"), "a dc inside a word is not the film")
        assertTrue(VideoMatcher.hasPublisherEvidence(sunTv, "Hangova", artist, "DC"))
        // Without the film, a TV channel or a label is still a publisher of the song it names.
        assertTrue(VideoMatcher.hasPublisherEvidence(sunTv, "Hangova", artist, null))
        for (label in listOf("T-Series", "Sony Music South", "Sun TV", "Sun Pictures")) {
            assertTrue(VideoMatcher.hasPublisherEvidence(VideoCandidate("LABELVIDEO1", "Song Name - Music Video | Film", label, 250, false, true),
                "Song Name", "Some Singer", null), label)
        }
    }

    @Test fun theSearchFindsTheSunTvVideoAndMeasuresItBeforeTheArtTracks() = runBlocking {
        val found = MusicVideoSearch(RecordedVideoSearch.replaying()).find("Hangova", artist, 248, album = "DC")
        val ids = found.map { it.videoId }
        assertTrue("wc8XNJ2x1Xs" in ids, "the official video is a candidate: $ids")
        // The art tracks are still pictures; the uploads that say they are the video are measured first.
        assertTrue(VideoMatcher.saysVideo(found.first().title), "first measured: ${found.first()}")
        assertTrue(ids.indexOf("wc8XNJ2x1Xs") < ids.indexOf("B6ppp6WBCKg"))
    }

    @Test fun theExpandedSearchNamesWhatItLeftOutAndStillFindsTheVideo() = runBlocking {
        val lines = mutableListOf<String>()
        val stills = setOf("B6ppp6WBCKg", "lccqcHmxSXM", "KNDFNLzdVsw")
        val found = MusicVideoSearch(RecordedVideoSearch.replaying()) { lines += it }
            .find("Hangova", artist, 248, limit = 10, album = "DC", exhaustive = true, excludedVideoIds = stills)
        assertTrue(found.any { it.videoId == "wc8XNJ2x1Xs" }, "eligible after the still pictures: ${found.map { it.videoId }}")
        val expanded = lines.filter { it.startsWith("expanded video search") }
        assertTrue(expanded.isNotEmpty())
        // The reaction uploads of the first expanded query are named with the reason they were left out.
        assertTrue(expanded.first().contains("left out") && expanded.first().contains("reaction"), expanded.first())
        assertTrue(expanded.none { it.endsWith("eligible ") }, "an empty eligible list is spelt out: $expanded")
    }

    @Test fun aSongFromTheSameFilmNeverTakesAnotherSongsVideo() = runBlocking {
        // Every query answered with the film's page: Hangova, Bum Baa Diga Diga and Raga of Revenge, all "| DC |".
        val filmPage = RecordedVideoSearch.answers.getValue("Hangova DC video song")
        val search = MusicVideoSearch(RecordedVideoSearch.client { filmPage })
        val aintNobody = search.find("Ain't Nobody", "Anirudh Ravichander, Heisenberg, Ram Kumar", 230, album = "DC", exhaustive = true)
        assertTrue(aintNobody.isEmpty(), "another song's video is no candidate: ${aintNobody.map { "${it.videoId} ${it.title}" }}")
        val raga = search.find("Raga of Revenge", "Anirudh Ravichander", 132, album = "DC").map { it.videoId }
        assertTrue("5_ndZJp-HJg" in raga, "$raga")
        assertTrue(raga.none { it in setOf("wc8XNJ2x1Xs", "8fYBnwVEdx8", "xKg3tQr8Cls") }, "$raga")
    }

    @Test fun reactionReviewAndCoverUploadsAreNeverCandidates() {
        val reaction = VideoCandidate("VzQCnPnTDbM", "One Name - Jailer 2 | Superstar Rajinikanth | Super Subu | Anirudh Video Song",
            "Producer Reacts", 321, false, true)
        assertNotNull(VideoMatcher.talkOverUpload(reaction, "One Name", "Anirudh Ravichander & Super Subu"))
        assertFalse(VideoMatcher.hasPublisherEvidence(reaction, "One Name", "Anirudh Ravichander & Super Subu", "Jailer 2"))
        assertFalse(VideoMatcher.hasFallbackEvidence(reaction, "One Name", "Anirudh Ravichander & Super Subu", "Jailer 2"))
        assertTrue(VideoMatcher.rank(listOf(reaction), "One Name", "Anirudh Ravichander & Super Subu", 205, album = "Jailer 2").isEmpty(),
            "never taken, even as the only match")
        for (title in listOf("One Name Video Song REACTION | Jailer 2", "One Name Song Review | Jailer 2", "First Time Hearing One Name | Jailer 2",
                "One Name Music Video Breakdown | Jailer 2", "One Name Dance Cover | Jailer 2 Video Song", "One Name - Jailer 2 video | Podcast Ep 4",
                "Reacting to One Name Video Song", "One Name Video Song Rxn | Jailer 2", "Live Stream: One Name video song")) {
            val upload = VideoCandidate("TALKOVER001", title, "Sun TV", 230, false, true)
            assertNotNull(VideoMatcher.talkOverUpload(upload, "One Name", "Anirudh Ravichander"), title)
            assertFalse(VideoMatcher.hasPublisherEvidence(upload, "One Name", "Anirudh Ravichander", "Jailer 2"), title)
        }
        for (channel in listOf("Tamil Reviews", "Reaction Bros", "ProducerReacts", "The Film Podcast", "Daily Vlogs", "Hangova Covers")) {
            assertNotNull(VideoMatcher.talkOverUpload(VideoCandidate("TALKOVER002", "One Name - Video Song | Jailer 2", channel, 230), "One Name"), channel)
        }
        // The song's own words do not count against it; the official video stays eligible.
        assertNull(VideoMatcher.talkOverUpload(VideoCandidate("COVERME0001", "Cover Me - Official Video", "Artist", 230), "Cover Me", "Artist"))
        assertNull(VideoMatcher.talkOverUpload(RecordedVideoSearch.candidate("Hangova DC video song", "wc8XNJ2x1Xs"), "Hangova", artist))
    }

    @Test fun aMatchFarLongerThanTheSongOrStoppedForTalkIsNotShown() {
        val upload = VideoCandidate("LONGUPLOAD1", "One Name - Video Song | Jailer 2", "Sun TV", 321, false, true)
        val partial = listOf(SyncSegment(0, 162_500, 50_454), SyncSegment(162_500, 204_160, 64_791))
        // The reported reaction: +57 % long, the song stopped for 14 s between its two parts.
        assertNotNull(VideoMatcher.alignedButWrong(upload, "One Name", "Anirudh Ravichander", 205, partial, 0.93))
        assertEquals(14_337, VideoMatcher.longestTalkGapMs(partial))
        // Length alone: +57 % with an ordinary match is suspect, with all of the song matched and no talk it is believed.
        val whole = listOf(SyncSegment(0, 205_000, 60_000))
        assertNotNull(VideoMatcher.alignedButWrong(upload, "One Name", "Anirudh Ravichander", 205, whole, 0.90))
        assertNull(VideoMatcher.alignedButWrong(upload, "One Name", "Anirudh Ravichander", 205, whole, 0.99))
        // Hangova's real map: 243 s video for a 248 s song, an edit that cuts the song shorter, never a hole.
        val hangova = listOf(SyncSegment(0, 107_500, 12_632), SyncSegment(107_500, 247_860, -889))
        val sunTv = RecordedVideoSearch.candidate("Hangova DC video song", "wc8XNJ2x1Xs")
        assertEquals(0, VideoMatcher.longestTalkGapMs(hangova))
        assertNull(VideoMatcher.alignedButWrong(sunTv, "Hangova", artist, 248, hangova, 0.95))
    }
}
