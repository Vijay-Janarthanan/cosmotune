package com.cosmotune.video.library

import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.flow.StateFlow

/** A followed channel (spec 8.1 `subscriptions.json`): id, name, avatar, when it was followed, the newest upload seen. */
data class Subscription(val channelId: String, val name: String, val avatarUrl: String?, val subscribedAtMs: Long, val lastUploadMs: Long?)

/** One watched video (spec 8.1 `history.json`): the item, last watched, the position, the duration, how many times watched. */
data class HistoryEntry(val item: VideoItem, val lastWatchedMs: Long, val positionMs: Long, val durationMs: Long, val watchCount: Int) {
    /**
     * Whether the video counts as watched (spec 5.0: "watched" at 90 %): the position is at least
     * [VideoMetrics.WATCHED_DONE] of the duration ([durationMs], else the item's own). Unknown durations are never watched.
     */
    val isWatched: Boolean
        get() {
            val total = item.effectiveDurationMs(durationMs) ?: return false
            return positionMs >= total * VideoMetrics.WATCHED_DONE.toDouble()
        }
}

/** One of your playlists (spec 8.1 `playlists.json`): id, name, ordered items, created and updated. */
data class LocalPlaylist(val id: String, val name: String, val items: List<VideoItem>, val createdMs: Long, val updatedMs: Long)

/**
 * The library that lives on the device (spec 8.1): subscriptions, history (with pause), watch later, liked, your
 * playlists, saved remote playlists and recent searches, plus export and import. The store is plan task Y-7; the screens
 * are built against [FakeVideoLibrary].
 */
interface VideoLibrary {
    /** Followed channels. */
    val subscriptions: StateFlow<List<Subscription>>

    /** Follows [channel] (no duplicates). */
    fun subscribe(channel: ChannelItem)

    /** Stops following [channelId]. */
    fun unsubscribe(channelId: String)

    /** Watched videos, the most recent first (spec 8.1: 10,000 entries). */
    val history: StateFlow<List<HistoryEntry>>

    /** Records [positionMs] of [durationMs] for [item] (spec 6.1: every 10 s and on pause and stop; nothing while paused). */
    fun recordWatch(item: VideoItem, positionMs: Long, durationMs: Long)

    /** The stored position of [videoId], or null when it was never watched (spec 6.1 resume). */
    fun positionOf(videoId: String): Long?

    /** Removes [videoId] from the history (spec 5.12). */
    fun removeFromHistory(videoId: String)

    /** Clears the whole history (spec 5.8). */
    fun clearHistory()

    /** Whether history is paused (spec 5.8, 5.11). */
    val historyPaused: StateFlow<Boolean>

    /** Pauses or resumes history. */
    fun setHistoryPaused(paused: Boolean)

    /** Watch later, in order (spec 5.8: 5,000). */
    val watchLater: StateFlow<List<VideoItem>>

    /** Adds [item] to watch later (no duplicates). */
    fun addToWatchLater(item: VideoItem)

    /** Removes [videoId] from watch later. */
    fun removeFromWatchLater(videoId: String)

    /** Moves the item at [from] to [to] in watch later (spec 5.8: watch later can be reordered). */
    fun moveInWatchLater(from: Int, to: Int)

    /** Liked videos, the most recent first (spec 5.8: 5,000). */
    val liked: StateFlow<List<VideoItem>>

    /** Likes or unlikes [item] locally (spec 5.15: a double tap on a Short adds it to Liked videos). */
    fun setLiked(item: VideoItem, liked: Boolean)

    /** Your playlists (spec 8.1: 500 playlists, 5,000 items each). */
    val playlists: StateFlow<List<LocalPlaylist>>

    /** Creates a playlist named [name] and returns its id. */
    fun createPlaylist(name: String): String

    /** Renames playlist [id]. */
    fun renamePlaylist(id: String, name: String)

    /** Deletes playlist [id]. */
    fun deletePlaylist(id: String)

    /** Adds [item] to playlist [id] (no duplicates). */
    fun addToPlaylist(id: String, item: VideoItem)

    /** Removes [videoId] from playlist [id]. */
    fun removeFromPlaylist(id: String, videoId: String)

    /** Moves the item at [from] to [to] in playlist [id]. */
    fun moveInPlaylist(id: String, from: Int, to: Int)

    /** Remote playlists you saved (spec 8.1: 1,000). */
    val savedPlaylists: StateFlow<List<PlaylistItem>>

    /** Saves a remote playlist. */
    fun savePlaylist(item: PlaylistItem)

    /** Forgets a saved remote playlist. */
    fun unsavePlaylist(id: String)

    /** Recent searches, the most recent first (spec 8.1: 50). */
    val searches: StateFlow<List<String>>

    /** Adds [query] to the recent searches. */
    fun addSearch(query: String)

    /** Removes [query] from the recent searches. */
    fun removeSearch(query: String)

    /** Clears the recent searches. */
    fun clearSearches()

    /** The whole library but the caches as one JSON document (spec 8.1: `cosmotune-video-<date>.json`). */
    fun exportJson(): String

    /** Merges [doc] into the library (spec 8.1, 8.2: union by id, the newer position wins; never deletes). */
    fun importDocument(doc: LibraryImport): ImportReport
}

/** What an importer read (spec 8.2), merged by [VideoLibrary.importDocument]. */
data class LibraryImport(
    val subscriptions: List<Subscription>,
    val history: List<HistoryEntry>,
    val playlists: List<LocalPlaylist>,
    val savedPlaylists: List<PlaylistItem>,
    val watchLater: List<VideoItem>,
    val liked: List<VideoItem>,
    val searches: List<String>,
)

/** What an import added (spec 8.2: "123 channels, 4,210 history entries, 6 playlists; 3 skipped"). */
data class ImportReport(val subscriptions: Int, val history: Int, val playlists: Int, val skipped: Int)
