package com.cosmotune.ui

import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.search.MyLibrary
import com.cosmotune.ui.search.browseTypeOf
import com.cosmotune.ui.search.searchMyLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Music's "In my library": the words are looked for in the songs you keep and the collections you saved, never on YouTube. */
class LibrarySearchTest {
    private fun song(id: String, title: String, artist: String, album: String? = null, video: Boolean = false) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, albumName = album, isVideo = video)

    private val library = MyLibrary.of(
        liked = listOf(song("a", "Kannalane", "A. R. Rahman", album = "Bombay"), song("b", "Roja Jaaneman", "A. R. Rahman")),
        added = listOf(song("a", "Kannalane", "A. R. Rahman", album = "Bombay")),
        downloads = listOf(song("c", "Kannalane (Video)", "A. R. Rahman", video = true)),
        shelves = listOf(
            HomeShelf("Albums", listOf(ShelfItem("Bombay", "A. R. Rahman", null, null, "MPREb_bombay"))),
            HomeShelf("Playlists", listOf(ShelfItem("Rahman evenings", "Playlist", null, null, "VLPLx"), ShelfItem("A song", "x", null, "vid", null))),
            HomeShelf("Artists", listOf(ShelfItem("A. R. Rahman", "Artist", null, null, "UCrahman"))),
        ),
    )

    @Test fun songsAreKeptOnceAndSavedItemsAreTheCollectionsNotTheSongsOnShelves() {
        assertEquals(listOf("a", "b", "c"), library.songs.map { it.videoId })
        assertEquals(listOf("MPREb_bombay", "VLPLx", "UCrahman"), library.collections.map { it.browseId })
        assertEquals(listOf(BrowseType.ALBUM, BrowseType.PLAYLIST, BrowseType.ARTIST), library.collections.map { it.type })
    }

    @Test fun everyWordMustMatchTheTitleArtistOrAlbum() {
        val rows = searchMyLibrary("rahman bombay", SearchFilter.ALL, library)
        assertEquals(listOf("a"), rows.filterIsInstance<SearchResult.Track>().map { it.song.videoId }, "the album counts")
        assertEquals(listOf("MPREb_bombay"), rows.filterIsInstance<SearchResult.Browse>().map { it.item.browseId })
        assertTrue(searchMyLibrary("   ", SearchFilter.ALL, library).isEmpty())
    }

    @Test fun theKindChipsNarrowItAsTheyNarrowAYouTubeSearch() {
        assertEquals(listOf("a", "b"), searchMyLibrary("rahman", SearchFilter.SONGS, library).map { (it as SearchResult.Track).song.videoId })
        assertEquals(listOf("c"), searchMyLibrary("rahman", SearchFilter.VIDEOS, library).map { (it as SearchResult.Track).song.videoId })
        assertEquals(listOf("UCrahman"), searchMyLibrary("rahman", SearchFilter.ARTISTS, library).map { (it as SearchResult.Browse).item.browseId })
        assertEquals(listOf("VLPLx"), searchMyLibrary("evenings", SearchFilter.PLAYLISTS, library).map { (it as SearchResult.Browse).item.browseId })
        assertEquals(listOf("MPREb_bombay"), searchMyLibrary("bombay", SearchFilter.ALBUMS, library).map { (it as SearchResult.Browse).item.browseId })
    }

    @Test fun aSavedItemsPageIdSaysWhatItIs() {
        assertEquals(BrowseType.ALBUM, browseTypeOf("MPREb_x"))
        assertEquals(BrowseType.ALBUM, browseTypeOf("OLAK5uy_x"))
        assertEquals(BrowseType.ARTIST, browseTypeOf("UCx"))
        assertEquals(BrowseType.PLAYLIST, browseTypeOf("VLPLx"))
    }
}
