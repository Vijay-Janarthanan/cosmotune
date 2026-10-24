package com.cosmotune.providers

import com.cosmotune.music.data.model.ArtistPage
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song

data class SourceCataloguePage(val collection: ProviderCollection, val songs: List<Song>, val fromCache: Boolean = false) {
    val shelves: List<HomeShelf> get() = collection.collections.takeIf { it.isNotEmpty() }?.let { pages ->
        listOf(HomeShelf("Albums", pages.map { page ->
            ShelfItem(page.title, page.subtitle, thumbnailUrl = page.artwork?.takeIf(CatalogueArtwork::handles), videoId = null, browseId = page.key.encode())
        }))
    }.orEmpty()

    fun artistPage(): ArtistPage = ArtistPage(songs = songs, moreSongsBrowseId = null,
        sections = shelves, name = collection.title, thumbnailUrl = collection.artwork?.takeIf(CatalogueArtwork::handles))
}
