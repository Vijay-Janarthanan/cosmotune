package com.cosmotune.design

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isSameTrackAs

/**
 * What the big play button of an artist page does and shows (the phone's `CosmoArtistPage` and the desktop's `CosmoDesktopArtistPage`
 * share this rule). While the song loaded in the player is one of the artist's own, the button is the player's play/pause: it reads
 * as pause while the audio plays, and a tap toggles the player instead of restarting the list from the top. Anywhere else (another
 * artist's song, nothing loaded, a page that was given no toggle) a tap plays the list.
 */
object ArtistPlay {
    enum class Tap { PLAY_LIST, TOGGLE }

    /** Whether the song that is loaded in the player ([current], playing or paused) is one of [songs]: the app's own "same track" test. */
    fun playerIsOn(songs: List<Song>, current: Song?): Boolean = current != null && songs.any { it.isSameTrackAs(current) }

    /** What a tap does: [Tap.TOGGLE] when the page can toggle ([canToggle]) and [playerIsOn] the artist, else [Tap.PLAY_LIST]. */
    fun tap(songs: List<Song>, current: Song?, canToggle: Boolean): Tap =
        if (canToggle && playerIsOn(songs, current)) Tap.TOGGLE else Tap.PLAY_LIST

    /** Whether the button draws as pause: one of the artist's songs is the one that is playing right now. */
    fun showsPause(songs: List<Song>, current: Song?, isPlaying: Boolean): Boolean = isPlaying && playerIsOn(songs, current)
}
