package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A song tagged "Kathal Nilavu" from "Malaigal Oyvatillai", sung by "Rensy", and its videos titled "Kadhal Nilavu … Malaigal
 * Oivathillai … Rency": the same words spelled another way in English letters (YouTube's search finds them; the matcher left
 * them out). Another song of the same film is still not the song.
 */
class VideoMatcherSpellingTest {
    private val title = "Kathal Nilavu"
    private val artist = "Rensy, Isai Raaja"
    private val album = "Malaigal Oyvatillai"

    @Test fun thePublishersVideoSpelledAnotherWayIsTheSong() {
        val artistOwn = VideoCandidate("POSTER00001", "Kadhal Nilavu Song | Malaigal Oivathillai | Isairaaja | Rency Paul", "Isairaaja Official", 286, isVerifiedChannel = true)
        val label = VideoCandidate("LABEL000001", "Kadhal Nilavu - Malaigal Oivathillai | Isairaaja, Rency Paul | Some Actor", "Southern Music", 274, isVerifiedChannel = true)
        assertTrue(VideoMatcher.hasPublisherEvidence(artistOwn, title, artist, album), "the composer's own channel")
        assertTrue(VideoMatcher.hasPublisherEvidence(label, title, artist, album), "a verified music label")
        assertTrue(VideoMatcher.hasFilmContext(label.title, album), "Oivathillai is Oyvatillai")
    }

    @Test fun uploadsThatSayTheyAreTheVideoSongGoBeforeAPublishersPlainSongUpload() {
        val poster = VideoCandidate("POSTER00001", "Kadhal Nilavu Song | Malaigal Oivathillai | Isairaaja | Rency Paul", "Isairaaja Official", 286, isVerifiedChannel = true)
        val label = VideoCandidate("LABEL000001", "Kadhal Nilavu - Malaigal Oivathillai | Isairaaja, Rency Paul | HD VideoSong", "Southern Music", 274, isVerifiedChannel = true)
        val anyone = VideoCandidate("ANYONE00001", "Malaigal Oivathillai Tamil Movie Songs | Kadhal Nilavu Video Song | Some Actor | Isairaja", "Film Songs Corner", 288)
        assertTrue(VideoMatcher.saysVideo(label.title), "VideoSong written as one word")
        val order = VideoMatcher.videoSongsFirst(listOf(poster), listOf(poster, anyone), title, artist, album).map { it.videoId }
        assertTrue(order.indexOf("ANYONE00001") < order.indexOf("POSTER00001"), "the video song is measured before the plain upload: $order")
        val publishersSayVideo = VideoMatcher.videoSongsFirst(listOf(label, poster), listOf(anyone), title, artist, album)
        assertTrue(publishersSayVideo.none { it.videoId == "ANYONE00001" }, "a publisher's own video song needs no one else's")
    }

    @Test fun anotherSongOfTheSameFilmIsStillNotTheSong() {
        val other = VideoCandidate("x", "Puthiya Paadal - Malaigal Oivathillai | Isairaaja | Video Song", "Southern Music", 270, isVerifiedChannel = true)
        assertFalse(VideoMatcher.hasPublisherEvidence(other, title, artist, album))
        val unverified = VideoCandidate("y", "Kadhal Nilavu | Malaigal Oivathillai | 8D audio", "Someone", 280)
        assertFalse(VideoMatcher.hasPublisherEvidence(unverified, title, artist, album), "an unverified upload is no publisher")
    }
}
