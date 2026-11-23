package com.cosmotune.ui.search

import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song

/*
 * Music's "In my library" (the search-spotlight board's 6a, the idea of BitChord 1.10's library-only search): the words are looked
 * for in what you keep, not on YouTube: your liked songs, the songs added to your library, your downloads, and the albums,
 * playlists and artists you saved. The kind chips narrow it as they narrow a YouTube search.
 */

/** What "In my library" looks in: the songs you keep (liked, added, downloaded) and the albums, playlists and artists you saved. */
class MyLibrary(val songs: List<Song>, val collections: List<BrowseItem>) {
    companion object {
        /**
         * The library as Music's pages hold it: [liked] and [added] songs, the [downloads], and every saved album, playlist and artist
         * on the library's [shelves] (each kept once, by its page id).
         */
        fun of(liked: List<Song>, added: List<Song>, downloads: List<Song>, shelves: List<HomeShelf>): MyLibrary = MyLibrary(
            songs = (liked + added + downloads).distinctBy { it.videoId },
            collections = shelves.flatMap { it.items }.mapNotNull { item ->
                val id = item.browseId?.takeIf { item.videoId == null } ?: return@mapNotNull null
                BrowseItem(id, item.title, item.subtitle, item.thumbnailUrl, browseTypeOf(id))
            }.distinctBy { it.browseId },
        )
    }
}

/** What a saved item's page id says it is: an album (`MPREb_`, `OLAK5uy_`), an artist (`UC`), else a playlist. */
fun browseTypeOf(browseId: String): BrowseType = when {
    browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> BrowseType.ALBUM
    browseId.startsWith("UC") -> BrowseType.ARTIST
    else -> BrowseType.PLAYLIST
}

/**
 * The rows of an "In my library" search for [query] under [filter]: the songs whose title, artist or album hold every word, then
 * the saved collections whose name or line does; "Videos" keeps the music videos among the songs. Nothing typed finds nothing.
 */
fun searchMyLibrary(query: String, filter: SearchFilter, library: MyLibrary): List<SearchResult> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return emptyList()
    fun matches(vararg fields: String?): Boolean {
        val text = fields.filterNotNull().joinToString(" ").lowercase()
        return words.all { it in text }
    }
    val songs = library.songs.filter { matches(it.title, it.artist, it.albumName) }
    val collections = library.collections.filter { matches(it.title, it.subtitle) }
    fun kind(type: BrowseType) = collections.filter { it.type == type }.map { SearchResult.Browse(it) }
    return when (filter) {
        SearchFilter.ALL -> songs.map { SearchResult.Track(it) } + collections.map { SearchResult.Browse(it) }
        SearchFilter.SONGS -> songs.filterNot { it.isVideo }.map { SearchResult.Track(it) }
        SearchFilter.VIDEOS -> songs.filter { it.isVideo }.map { SearchResult.Track(it) }
        SearchFilter.ALBUMS -> kind(BrowseType.ALBUM)
        SearchFilter.ARTISTS -> kind(BrowseType.ARTIST)
        SearchFilter.PLAYLISTS -> kind(BrowseType.PLAYLIST)
    }
}
