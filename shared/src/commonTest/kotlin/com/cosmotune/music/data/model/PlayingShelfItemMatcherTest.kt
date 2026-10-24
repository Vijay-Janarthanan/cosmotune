package com.cosmotune.music.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which card of a shelf lights for the song that is playing: the one that was played, not every namesake (BC-30). */
class PlayingShelfItemMatcherTest {

    private fun card(id: String?, title: String = "Song", subtitle: String = "Artist", browse: String? = null) =
        ShelfItem(title = title, subtitle = subtitle, thumbnailUrl = null, videoId = id, browseId = browse)

    private fun song(id: String, title: String = "Song", artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun lit(items: List<ShelfItem>, current: Song?): List<String?> =
        items.filter(playingShelfItemMatcher(items, current)).map { it.videoId ?: it.browseId }

    @Test
    fun theCardThatWasPlayedLightsAloneWhenANamesakeSharesTheShelf() {
        val shelf = listOf(card("track"), card("video"))
        assertEquals(listOf("video"), lit(shelf, song("video")))
        assertEquals(listOf("track"), lit(shelf, song("track")))
    }

    @Test
    fun withNoCardByIdTheFirstSongCardWithTheNameLights() {
        val shelf = listOf(card("other", title = "Else"), card("yt1"), card("yt2"))
        assertEquals(listOf("yt1"), lit(shelf, song("local:42")))
    }

    @Test
    fun albumPlaylistAndArtistCardsNeverLight() {
        // An album card titled like the song (a single) has no video id: it is not the song.
        val shelf = listOf(card(null, browse = "MPREb_single"), card("yt1"))
        assertEquals(listOf("yt1"), lit(shelf, song("local:1")))
        assertEquals(emptyList(), lit(listOf(card(null, browse = "MPREb_single")), song("local:1")))
    }

    @Test
    fun nothingLightsWithNothingPlayingOrNoMatch() {
        assertEquals(emptyList(), lit(listOf(card("a")), null))
        assertEquals(emptyList(), lit(listOf(card("a", title = "Other")), song("b")))
    }

    @Test
    fun theOldRuleLitEveryNamesakeAndThisDoesNot() {
        // The rule both Homes used: same video, or the same title and credit, on every card.
        fun old(item: ShelfItem, current: Song) = item.videoId != null &&
            (item.videoId == current.videoId || (item.title == current.title && item.subtitle == current.artist))
        val shelf = listOf(card("track"), card("video"), card("live"))
        val playing = song("video")
        assertEquals(3, shelf.count { old(it, playing) })
        assertEquals(1, shelf.count(playingShelfItemMatcher(shelf, playing)))
    }
}
