package com.cosmotune.design

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a tap on the artist page's big play button does (P4-20): while the player is on one of the artist's songs, and the page was
 * given a play/pause callback, the tap toggles the player; otherwise it plays the list from the top, as it always did.
 */
class ArtistPlayTest {
    private fun song(title: String, artist: String = "Artist") = Song(videoId = "id-$title", title = title, artist = artist, thumbnailUrl = null)

    private val songs = listOf(song("One"), song("Two"), song("Three"))

    @Test fun aSongOfTheListThatIsLoadedMakesTheTapAToggle() {
        assertEquals(ArtistPlay.Tap.TOGGLE, ArtistPlay.tap(songs, current = song("Two"), canToggle = true))
    }

    @Test fun withoutACallbackTheTapAlwaysPlaysTheList() {
        assertEquals(ArtistPlay.Tap.PLAY_LIST, ArtistPlay.tap(songs, current = song("Two"), canToggle = false))
    }

    @Test fun aSongOfSomeoneElseOrNoSongPlaysTheList() {
        assertEquals(ArtistPlay.Tap.PLAY_LIST, ArtistPlay.tap(songs, current = song("Other", artist = "Someone else"), canToggle = true))
        assertEquals(ArtistPlay.Tap.PLAY_LIST, ArtistPlay.tap(songs, current = null, canToggle = true))
    }

    @Test fun anEmptyListPlaysTheListWhateverIsLoaded() {
        assertEquals(ArtistPlay.Tap.PLAY_LIST, ArtistPlay.tap(emptyList(), current = song("Two"), canToggle = true))
    }

    @Test fun theSameTrackIsTheOneTheAppCallsTheSameTrack() {
        // the page's rows and the player's queue carry different ids for one recording: title and artist are what match (isSameTrackAs)
        val loaded = Song(videoId = "another-id", title = "Two", artist = "Artist", thumbnailUrl = null)
        assertTrue(ArtistPlay.playerIsOn(songs, loaded))
    }

    @Test fun theButtonReadsAsPauseOnlyWhileAnArtistSongIsActuallyPlaying() {
        assertTrue(ArtistPlay.showsPause(songs, current = song("One"), isPlaying = true))
        // paused on the artist's song: the button offers play, and the tap resumes (it is a toggle)
        assertFalse(ArtistPlay.showsPause(songs, current = song("One"), isPlaying = false))
        assertEquals(ArtistPlay.Tap.TOGGLE, ArtistPlay.tap(songs, current = song("One"), canToggle = true))
        assertFalse(ArtistPlay.showsPause(songs, current = song("Other", artist = "Someone else"), isPlaying = true))
        assertFalse(ArtistPlay.showsPause(songs, current = null, isPlaying = true))
    }
}
