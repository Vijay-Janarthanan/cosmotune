package com.cosmotune.design

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.settings.LibrarySort

/** The four segments of the new Library page, in the order of the control (`V2.library`, `.seg2`). */
enum class LibrarySegment { PLAYLISTS, ALBUMS, ARTISTS, DOWNLOADED }

/**
 * The pure rules of the Library page in the new look: which shelf of the feed a segment shows, which playlist takes the liked
 * tile and which the mix tile, and the two orders the old page already had (pinned first, and the title sort of "Show all").
 *
 * This file sits in `jvmSharedMain` rather than `commonMain` because [LibrarySort] and [YtMusicRepository] (whose
 * `PLAYLISTS_SHELF` and `LIKED_MUSIC` are the names the rest of the app looks for) live there.
 */
object LibrarySegments {
    /**
     * What the Albums, Artists and Subscriptions shelves are called in a `LibraryPage`: the repository names them in its
     * private `LIBRARY_FEEDS` list and exports only the Playlists one, so the others are matched by these literals.
     */
    const val ALBUMS_SHELF = "Albums"
    const val ARTISTS_SHELF = "Artists"
    const val SUBSCRIPTIONS_SHELF = "Subscriptions"

    /** A rail of any other shelf never swipes past this many cards (`LIBRARY_ROW_MAX_ITEMS` of the old page). */
    const val RAIL_MAX_ITEMS = 5

    /** Whether [item] is the account's Liked Music playlist: browse id `VLLM` ([YtMusicRepository.LIKED_MUSIC]) or `LM`. */
    fun isLiked(item: ShelfItem): Boolean = item.browseId == YtMusicRepository.LIKED_MUSIC || item.browseId == "LM"

    /**
     * The shelf a segment shows: Playlists and Albums are the shelf of that title; Artists are the Artists shelf and the
     * Subscriptions shelf joined (an artist in both is listed once, where it first appears) under the Artists title.
     * Null when the feed has none of them (and always for [LibrarySegment.DOWNLOADED], which is not a shelf of the feed).
     */
    fun shelfFor(segment: LibrarySegment, shelves: List<HomeShelf>): HomeShelf? = when (segment) {
        LibrarySegment.PLAYLISTS -> shelves.firstOrNull { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        LibrarySegment.ALBUMS -> shelves.firstOrNull { it.title == ALBUMS_SHELF }
        LibrarySegment.ARTISTS -> {
            val found = shelves.filter { it.title == ARTISTS_SHELF || it.title == SUBSCRIPTIONS_SHELF }
            if (found.isEmpty()) null else HomeShelf(title = ARTISTS_SHELF, items = distinctById(found.flatMap { it.items }))
        }
        LibrarySegment.DOWNLOADED -> null
    }

    /** Every shelf that is not the playlists, the albums or the artists (podcasts and whatever else the feed holds), in order. */
    fun otherShelves(shelves: List<HomeShelf>): List<HomeShelf> =
        shelves.filter { it.title != YtMusicRepository.PLAYLISTS_SHELF && it.title != ALBUMS_SHELF && it.title != ARTISTS_SHELF && it.title != SUBSCRIPTIONS_SHELF }

    /**
     * Moves whichever of [items] are in [pinned] to the front, in the order they were pinned, leaving everything else in its
     * existing order behind them. The rule of the private `HomeShelf.pinnedFirst` of `LibraryScreen.kt`, on a list; a pin
     * listed twice moves its item once (the old function would have repeated it, and a repeated item is a repeated lazy key).
     */
    fun pinnedFirst(items: List<ShelfItem>, pinned: List<String>): List<ShelfItem> {
        if (pinned.isEmpty()) return items
        val byId = items.filter { it.browseId != null }.associateBy { it.browseId }
        val pinnedItems = pinned.distinct().mapNotNull { byId[it] }
        if (pinnedItems.isEmpty()) return items
        val pinnedSet = pinnedItems.toSet()
        return pinnedItems + items.filter { it !in pinnedSet }
    }

    /**
     * The order of a "Show all" grid: a card carries only a title, so [LibrarySort.DEFAULT] (the feed's own order) is the only
     * option that is not alphabetical. The rule of the private `HomeShelf.sortedForLibrary` of `LibraryScreen.kt`.
     */
    fun sorted(items: List<ShelfItem>, sort: LibrarySort): List<ShelfItem> = when (sort) {
        LibrarySort.DEFAULT -> items
        LibrarySort.TITLE_ASC -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        LibrarySort.TITLE_DESC -> items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** What the Playlists segment draws above its list: the two pinned tiles, and the playlists left for the rows. */
    data class PlaylistLayout(
        /** The Liked Music playlist, for the gradient tile. */
        val liked: ShelfItem?,
        /** The playlist for the mix tile. */
        val mix: ShelfItem?,
        /** Every other playlist, pinned first. */
        val rows: List<ShelfItem>,
    )

    /**
     * Splits the playlists. The liked playlist (the first [isLiked] one) takes the liked tile. The mix tile takes the first
     * pinned playlist, or else the first playlist with art; never a liked one. Everything else is a row, pinned first, so a
     * playlist is drawn once: on a tile or in the list.
     */
    fun playlistLayout(items: List<ShelfItem>, pinned: List<String>): PlaylistLayout {
        val ordered = pinnedFirst(items, pinned)
        val likedIndex = ordered.indexOfFirst { isLiked(it) }
        val liked = ordered.getOrNull(likedIndex)
        val rest = ordered.filterIndexed { index, _ -> index != likedIndex }
        val pinnedSet = pinned.toSet()
        val mixIndex = rest.indexOfFirst { !isLiked(it) && it.browseId != null && it.browseId in pinnedSet }
            .takeIf { it >= 0 } ?: rest.indexOfFirst { !isLiked(it) && it.thumbnailUrl != null }
        val mix = rest.getOrNull(mixIndex)
        return PlaylistLayout(liked, mix, rest.filterIndexed { index, _ -> index != mixIndex })
    }

    /** [items] without a second item of the same browse id; items with no id are all kept. */
    private fun distinctById(items: List<ShelfItem>): List<ShelfItem> {
        val seen = HashSet<String>()
        return items.filter { it.browseId == null || seen.add(it.browseId) }
    }
}
