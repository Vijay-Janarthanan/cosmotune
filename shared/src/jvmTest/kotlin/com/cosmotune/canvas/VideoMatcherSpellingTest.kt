package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A song tagged "Kathal Oviyam" from "Alaigal Oyvatillai", sung by "Jensy", and its videos titled "Kadhal Oviyam … Alaigal
 * Oivathillai … Jency": the same words spelled another way in English letters (YouTube's search finds them; the matcher left
 * them out). Another song of the same film is still not the song.
 */
class VideoMatcherSpellingTest {
    private val title = "Kathal Oviyam"
    private val artist = "Jensy, Ilaiya Raaja"
    private val album = "Alaigal Oyvatillai"

    @Test fun thePublishersVideoSpelledAnotherWayIsTheSong() {
        val artistOwn = VideoCandidate("s_4HQnCki4o", "Kadhal Oviyam Song | Alaigal Oivathillai | Ilaiyaraaja | Jency Anthony", "Ilaiyaraaja Official", 286, isVerifiedChannel = true)
        val label = VideoCandidate("KdZ4R5GxIDI", "Kadhal Oviyam - Alaigal Oivathillai | Ilaiyaraaja, Jency Anthony | Pandiyan", "7th Channel Music", 274, isVerifiedChannel = true)
        assertTrue(VideoMatcher.hasPublisherEvidence(artistOwn, title, artist, album), "the composer's own channel")
        assertTrue(VideoMatcher.hasPublisherEvidence(label, title, artist, album), "a verified music label")
        assertTrue(VideoMatcher.hasFilmContext(label.title, album), "Oivathillai is Oyvatillai")
    }

    @Test fun anotherSongOfTheSameFilmIsStillNotTheSong() {
        val other = VideoCandidate("x", "Puthiya Poovithu - Alaigal Oivathillai | Ilaiyaraaja | Video Song", "7th Channel Music", 270, isVerifiedChannel = true)
        assertFalse(VideoMatcher.hasPublisherEvidence(other, title, artist, album))
        val unverified = VideoCandidate("y", "Kadhal Oviyam | Alaigal Oivathillai | 8D audio", "Someone", 280)
        assertFalse(VideoMatcher.hasPublisherEvidence(unverified, title, artist, album), "an unverified upload is no publisher")
    }
}
