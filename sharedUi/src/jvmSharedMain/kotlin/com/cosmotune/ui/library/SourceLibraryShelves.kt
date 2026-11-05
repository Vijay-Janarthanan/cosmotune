package com.cosmotune.ui.library

import androidx.compose.runtime.*
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.providers.ProviderBrowseKey
import com.cosmotune.providers.ProviderHub
import com.cosmotune.providers.SourceLibraryState
import com.cosmotune.providers.SourceLibraryStore
import kotlinx.coroutines.flow.MutableStateFlow

/** Device-owned catalogue shelves remain available while signed out of a YouTube account. */
@Composable
fun rememberSourceLibraryShelves(): List<HomeShelf> {
    val hub by ProviderHub.state.collectAsState()
    val empty = remember { MutableStateFlow(SourceLibraryState()) }
    val library by (hub.library?.state ?: empty).collectAsState()
    return remember(library) { sourceLibraryShelves(library) }
}

internal fun sourceLibraryShelves(library: SourceLibraryState): List<HomeShelf> = buildList {
    val liked = library.liked
    if (liked.isNotEmpty()) add(HomeShelf("Source favourites", listOf(ShelfItem("Liked source songs", "${liked.size} songs",
        liked.firstOrNull()?.thumbnailUrl, null, SourceLibraryStore.LIKED_BROWSE_ID)), sourceId = "source-library:playlists"))
    for ((kind, title) in listOf("album" to "Source albums", "playlist" to "Source playlists")) {
        val items = library.collections.filter { ProviderBrowseKey.parse(it.id)?.kind == kind }.sortedByDescending { it.savedAtMs }.map { it.card() }
        if (items.isNotEmpty()) add(HomeShelf(title, items, sourceId = "source-library:${if (kind == "album") "albums" else "playlists"}"))
    }
    if (library.followed.isNotEmpty()) add(HomeShelf("Followed source artists", library.followed.sortedByDescending { it.savedAtMs }.map { it.card() }, sourceId = "source-library:artists"))
}
