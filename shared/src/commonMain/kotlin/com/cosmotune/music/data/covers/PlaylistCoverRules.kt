package com.cosmotune.music.data.covers

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.Song

/**
 * Which cover a playlist shows: the picture the person picked for one of their own playlists, or else the one YouTube Music makes
 * from its songs. The picked covers are a map of playlist id to the picture's address ([PlaylistCoverStore.covers]). Pure, so it is
 * tested on the JVM.
 */
object PlaylistCoverRules {
    /** Longest id kept: a playlist id is a few dozen characters, and the id is also a file name and a key in the account. */
    private const val MAX_ID = 200

    /**
     * The id a playlist's cover is kept under: the playlist id, the same whether it was given as a page id ("VLPL...") or as the
     * playlist's own ("PL..."). Null for anything that is not a YouTube playlist id (a device playlist, a downloads page).
     */
    fun key(id: String?): String? {
        val key = id?.removePrefix("VL") ?: return null
        if (key.isEmpty() || key.length > MAX_ID) return null
        return key.takeIf { it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' } }
    }

    /** The cover to show for the playlist [browseId]: the picked one, or [automatic]. */
    fun coverFor(covers: Map<String, String>, browseId: String?, automatic: String?): String? =
        key(browseId)?.let(covers::get) ?: automatic

    fun hasCustom(covers: Map<String, String>, browseId: String?): Boolean = key(browseId)?.let(covers::containsKey) == true

    /** The Library's cards with the picked covers in: a playlist's card (no song id on it) for a playlist with a picked cover. */
    fun shelvesWithCovers(shelves: List<HomeShelf>, covers: Map<String, String>): List<HomeShelf> {
        if (covers.isEmpty()) return shelves
        return shelves.map { shelf ->
            if (shelf.items.none { it.videoId == null && hasCustom(covers, it.browseId) }) return@map shelf
            shelf.copy(
                items = shelf.items.map { item ->
                    if (item.videoId != null) item else coverFor(covers, item.browseId, null)?.let { item.copy(thumbnailUrl = it) } ?: item
                },
            )
        }
    }

    /**
     * [url] as it may leave the page: a picture kept in the app's own files (a picked cover) is only good on this device, and is
     * deleted when the cover changes, so it is never stored with a download or sent anywhere. Null for such a picture.
     */
    fun portable(url: String?): String? = url?.takeUnless { it.startsWith("file:") }

    /** The pictures the picker offers first: each song cover of the playlist once, in its order, at most [limit]. */
    fun songChoices(songs: List<Song>, limit: Int = 24): List<String> =
        songs.asSequence().mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(limit).toList()
}
