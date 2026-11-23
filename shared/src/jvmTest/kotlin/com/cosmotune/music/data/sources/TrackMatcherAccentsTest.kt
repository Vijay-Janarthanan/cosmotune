package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Accents in Latin titles and artists do not keep a song from its upload (adapted from the upstream test, GPL-3.0). */
class TrackMatcherAccentsTest {
    private fun song(title: String, artist: String) = Song(videoId = "x", title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun aTurkishTitleMatchesItsUnaccentedUpload() {
        assertTrue(TrackMatcher.matches(song("Simarik", "Tarkan"), "Şımarık", "Tarkan"))
        assertTrue(TrackMatcher.matches(song("Şımarık", "Tarkan"), "Simarik", "Tarkan"))
    }

    @Test
    fun anAccentedArtistMatchesItsUnaccentedSpelling() {
        assertTrue(TrackMatcher.matches(song("Nilüfer", "Muslum Gurses"), "Nilüfer", "Müslüm Gürses"))
        assertTrue(TrackMatcher.matches(song("Deja vu", "Beyonce"), "Déjà Vu", "Beyoncé"))
    }

    @Test
    fun aDifferentTitleStillDoesNotMatch() {
        assertFalse(TrackMatcher.matches(song("Kuzu Kuzu", "Tarkan"), "Şımarık", "Tarkan"))
    }

    @Test
    fun otherScriptsKeepTheirMarks() {
        // Hindi vowel signs are combining marks; they tell these two words apart.
        assertNotEquals(TrackMatcher.foldLatin("दिल"), TrackMatcher.foldLatin("दल"))
        assertTrue(TrackMatcher.foldLatin("Ünïcödé") == "Unicode")
    }

    @Test
    fun aCjkTitlesAccentedRomanisationStillFilesTheUpload() {
        val cjk = TrackMatcher.parseTitle("東京 (Tōkyō)")
        val latin = TrackMatcher.parseTitle("Tōkyō")
        assertTrue(TrackMatcher.isTranslationMatch(cjk, latin))
        assertTrue(TrackMatcher.isTranslationMatch(latin, cjk))
    }
}
