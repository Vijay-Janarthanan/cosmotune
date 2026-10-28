package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YouTube Music files the composer of "Machaan Machaan" as "Yuvanshankar Raja"; Amazon Music spells
 * it "Yuvan Shankar Raja" among two other credits. As whole words neither is a run of the other, so
 * every Amazon row was refused as a different artist and the song never went to Amazon at all.
 */
class DesktopArtistSpellingTest {

    private val playing = Song(videoId = "6vrBoLtuiKc", title = "Machaan Machaan", artist = "Yuvanshankar Raja", thumbnailUrl = null, durationText = "3:43")

    private fun amazon(title: String, artist: String) =
        Song(videoId = "sflx-amazon:abc", title = title, artist = artist, thumbnailUrl = null, durationText = "3:43")

    @Test fun theSameNameSpelledWithDifferentWordBreaksIsTheSameArtist() {
        val row = amazon("Machaan Machaan (From \"Silambattam\")", "Ilaiyaraaja, Yuvan Shankar Raja & Bela Shende")
        assertNotNull(DesktopTrackMatcher.best(listOf(row), playing))
        assertEquals(1, DesktopTrackMatcher.ranked(listOf(row), playing).size)
    }

    @Test fun theSharedMatcherAgrees() {
        assertTrue(TrackMatcher.sharesArtist("Yuvanshankar Raja", "Ilaiyaraaja, Yuvan Shankar Raja & Bela Shende"))
        assertTrue(TrackMatcher.sharesArtist("Yuvan Shankar Raja", "Yuvanshankar Raja"))
    }

    @Test fun aDifferentArtistWhoSharesAWordIsStillRefused() {
        assertNull(DesktopTrackMatcher.best(listOf(amazon("Machaan Machaan", "Shankar Mahadevan")), playing))
        assertFalse(TrackMatcher.sharesArtist("Yuvanshankar Raja", "Shankar Mahadevan"))
        assertFalse(TrackMatcher.sharesArtist("Yuvan Shankar Raja", "Shankar Mahadevan & Raja Kumari"))
    }

    @Test fun nearlyTheSameLettersAreNotTheSameName() {
        assertFalse(TrackMatcher.sharesArtist("Ed Sheeran", "Ed Sheerin"))
        assertFalse(TrackMatcher.sharesArtist("Queen", "Queensryche"))
        assertFalse(TrackMatcher.sharesArtist("Ann Abel", "Annabelle"))
    }

    @Test fun twoSingleWordNamesStillNeedToBeEqual() {
        assertTrue(TrackMatcher.sharesArtist("Rahman", "A. R. Rahman"))
        assertFalse(TrackMatcher.sharesArtist("Rahman", "Rahmani"))
    }
}
