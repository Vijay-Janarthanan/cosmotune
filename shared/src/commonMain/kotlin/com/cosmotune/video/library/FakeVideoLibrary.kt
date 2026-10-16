package com.cosmotune.video.library

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.jvm.Synchronized

/**
 * The library in memory (plan task Y-2, step 3): the real behaviour of spec 8.1 without files, so screens can be rendered
 * and tested. No duplicates; the most recent first for history, liked, saved playlists and searches; a watch counts again
 * after 30 minutes; the caps of spec 8.1 (history 10,000, watch later and liked 5,000 each, 500 playlists of 5,000 items,
 * 1,000 saved playlists, 50 searches); nothing is recorded while history is paused. [withSamples] gives one already filled.
 */
class FakeVideoLibrary(private val clock: () -> Long = { FakeVideoSource.NOW_MS }) : VideoLibrary {
    private val subscriptionFlow = MutableStateFlow<List<Subscription>>(emptyList())
    private val historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val pausedFlow = MutableStateFlow(false)
    private val watchLaterFlow = MutableStateFlow<List<VideoItem>>(emptyList())
    private val likedFlow = MutableStateFlow<List<VideoItem>>(emptyList())
    private val playlistFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    private val savedFlow = MutableStateFlow<List<PlaylistItem>>(emptyList())
    private val searchFlow = MutableStateFlow<List<String>>(emptyList())
    private var playlistCounter = 0

    override val subscriptions: StateFlow<List<Subscription>> = subscriptionFlow.asStateFlow()
    override val history: StateFlow<List<HistoryEntry>> = historyFlow.asStateFlow()
    override val historyPaused: StateFlow<Boolean> = pausedFlow.asStateFlow()
    override val watchLater: StateFlow<List<VideoItem>> = watchLaterFlow.asStateFlow()
    override val liked: StateFlow<List<VideoItem>> = likedFlow.asStateFlow()
    override val playlists: StateFlow<List<LocalPlaylist>> = playlistFlow.asStateFlow()
    override val savedPlaylists: StateFlow<List<PlaylistItem>> = savedFlow.asStateFlow()
    override val searches: StateFlow<List<String>> = searchFlow.asStateFlow()

    override fun subscribe(channel: ChannelItem) = subscriptionFlow.update { list ->
        val existing = list.firstOrNull { it.channelId == channel.id }
        if (existing != null) list.map { if (it.channelId == channel.id) it.copy(name = channel.name, avatarUrl = channel.avatarUrl ?: it.avatarUrl) else it }
        else list + Subscription(channel.id, channel.name, channel.avatarUrl, clock(), null)
    }

    override fun unsubscribe(channelId: String) = subscriptionFlow.update { list -> list.filterNot { it.channelId == channelId } }

    override fun recordWatch(item: VideoItem, positionMs: Long, durationMs: Long) {
        if (pausedFlow.value) return
        val now = clock()
        historyFlow.update { list ->
            val previous = list.firstOrNull { it.item.id == item.id }
            val count = when {
                previous == null -> 1
                now - previous.lastWatchedMs > REWATCH_AFTER_MS -> previous.watchCount + 1
                else -> previous.watchCount
            }
            (listOf(HistoryEntry(item, now, positionMs, durationMs, count)) + list.filterNot { it.item.id == item.id }).take(HISTORY_CAP)
        }
    }

    override fun positionOf(videoId: String): Long? = historyFlow.value.firstOrNull { it.item.id == videoId }?.positionMs

    override fun removeFromHistory(videoId: String) = historyFlow.update { list -> list.filterNot { it.item.id == videoId } }

    override fun clearHistory() {
        historyFlow.value = emptyList()
    }

    override fun setHistoryPaused(paused: Boolean) {
        pausedFlow.value = paused
    }

    override fun addToWatchLater(item: VideoItem) = watchLaterFlow.update { list ->
        if (list.any { it.id == item.id }) list else (list + item).take(LIST_CAP)
    }

    override fun removeFromWatchLater(videoId: String) = watchLaterFlow.update { list -> list.filterNot { it.id == videoId } }

    override fun moveInWatchLater(from: Int, to: Int) = watchLaterFlow.update { it.moved(from, to) }

    override fun setLiked(item: VideoItem, liked: Boolean) = likedFlow.update { list ->
        val rest = list.filterNot { it.id == item.id }
        if (liked) (listOf(item) + rest).take(LIST_CAP) else rest
    }

    @Synchronized
    override fun createPlaylist(name: String): String {
        playlistCounter += 1
        val id = "localplaylist-$playlistCounter"
        val now = clock()
        playlistFlow.update { list -> if (list.size >= PLAYLIST_CAP) list else list + LocalPlaylist(id, name, emptyList(), now, now) }
        return id
    }

    override fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    override fun deletePlaylist(id: String) = playlistFlow.update { list -> list.filterNot { it.id == id } }

    override fun addToPlaylist(id: String, item: VideoItem) = editPlaylist(id) { p ->
        if (p.items.any { it.id == item.id } || p.items.size >= PLAYLIST_ITEM_CAP) p else p.copy(items = p.items + item)
    }

    override fun removeFromPlaylist(id: String, videoId: String) = editPlaylist(id) { p -> p.copy(items = p.items.filterNot { it.id == videoId }) }

    override fun moveInPlaylist(id: String, from: Int, to: Int) = editPlaylist(id) { p -> p.copy(items = p.items.moved(from, to)) }

    override fun savePlaylist(item: PlaylistItem) = savedFlow.update { list ->
        (listOf(item) + list.filterNot { it.id == item.id }).take(SAVED_CAP)
    }

    override fun unsavePlaylist(id: String) = savedFlow.update { list -> list.filterNot { it.id == id } }

    override fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchFlow.update { list -> (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(SEARCH_CAP) }
    }

    override fun removeSearch(query: String) = searchFlow.update { list -> list.filterNot { it == query } }

    override fun clearSearches() {
        searchFlow.value = emptyList()
    }

    /** The export document of spec 8.1 (plan task Y-7's shape): `format`, `version`, `exportedAt` and every list but the caches. */
    override fun exportJson(): String = buildJsonObject {
        put("format", "cosmotune-video")
        put("version", 1)
        put("exportedAt", clock())
        putJsonArray("subscriptions") {
            for (s in subscriptionFlow.value) addJsonObject {
                put("channelId", s.channelId); put("name", s.name); put("avatarUrl", s.avatarUrl)
                put("subscribedAtMs", s.subscribedAtMs); put("lastUploadMs", s.lastUploadMs)
            }
        }
        putJsonArray("history") {
            for (h in historyFlow.value) addJsonObject {
                put("item", itemJson(h.item)); put("lastWatchedMs", h.lastWatchedMs); put("positionMs", h.positionMs)
                put("durationMs", h.durationMs); put("watchCount", h.watchCount)
            }
        }
        put("watchLater", buildJsonArray { for (i in watchLaterFlow.value) add(itemJson(i)) })
        put("liked", buildJsonArray { for (i in likedFlow.value) add(itemJson(i)) })
        putJsonArray("playlists") {
            for (p in playlistFlow.value) addJsonObject {
                put("id", p.id); put("name", p.name); put("createdMs", p.createdMs); put("updatedMs", p.updatedMs)
                put("items", buildJsonArray { for (i in p.items) add(itemJson(i)) })
            }
        }
        putJsonArray("savedPlaylists") {
            for (p in savedFlow.value) addJsonObject {
                put("id", p.id); put("title", p.title); put("thumbnailUrl", p.thumbnailUrl); put("ownerName", p.ownerName)
                put("ownerId", p.ownerId); put("videoCount", p.videoCount)
            }
        }
        putJsonArray("searches") { for (q in searchFlow.value) add(kotlinx.serialization.json.JsonPrimitive(q)) }
    }.toString()

    /**
     * Merges [doc] (spec 8.1, 8.2): union by id; for a video in both histories the newer watch wins; playlists merge by name
     * (items appended without duplicates); nothing is deleted. The report counts what was added or updated.
     */
    @Synchronized
    override fun importDocument(doc: LibraryImport): ImportReport {
        val subscriptionsBefore = subscriptionFlow.value
        val knownChannels = subscriptionsBefore.map { it.channelId }.toMutableSet()
        val newSubscriptions = doc.subscriptions.filter { knownChannels.add(it.channelId) }
        subscriptionFlow.value = subscriptionsBefore + newSubscriptions

        val byId = historyFlow.value.associateBy { it.item.id }.toMutableMap()
        var historyChanged = 0
        for (entry in doc.history) {
            val mine = byId[entry.item.id]
            if (mine == null || entry.lastWatchedMs > mine.lastWatchedMs) {
                byId[entry.item.id] = entry
                historyChanged += 1
            }
        }
        historyFlow.value = byId.values.sortedByDescending { it.lastWatchedMs }.take(HISTORY_CAP)

        var playlists = playlistFlow.value
        var playlistsChanged = 0
        for (incoming in doc.playlists) {
            val same = playlists.firstOrNull { it.name == incoming.name }
            if (same == null) {
                if (playlists.size >= PLAYLIST_CAP) continue
                playlists = playlists + incoming
            } else {
                val added = incoming.items.filter { i -> same.items.none { it.id == i.id } }
                playlists = playlists.map { if (it.id == same.id) it.copy(items = (it.items + added).take(PLAYLIST_ITEM_CAP), updatedMs = clock()) else it }
            }
            playlistsChanged += 1
        }
        playlistFlow.value = playlists

        val saved = savedFlow.value
        savedFlow.value = (saved + doc.savedPlaylists.filter { s -> saved.none { it.id == s.id } }.distinctBy { it.id }).take(SAVED_CAP)
        val later = watchLaterFlow.value
        watchLaterFlow.value = (later + doc.watchLater.filter { v -> later.none { it.id == v.id } }.distinctBy { it.id }).take(LIST_CAP)
        val liked = likedFlow.value
        likedFlow.value = (liked + doc.liked.filter { v -> liked.none { it.id == v.id } }.distinctBy { it.id }).take(LIST_CAP)
        val searches = searchFlow.value
        searchFlow.value = (searches + doc.searches.filter { q -> searches.none { it.equals(q, ignoreCase = true) } }.distinct()).take(SEARCH_CAP)
        return ImportReport(subscriptions = newSubscriptions.size, history = historyChanged, playlists = playlistsChanged, skipped = 0)
    }

    private fun editPlaylist(id: String, edit: (LocalPlaylist) -> LocalPlaylist) {
        val now = clock()
        playlistFlow.update { list -> list.map { if (it.id == id) edit(it).copy(updatedMs = now) else it } }
    }

    private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices || from == to) return this
        val result = toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    private fun itemJson(i: VideoItem): JsonObject = buildJsonObject {
        put("id", i.id); put("title", i.title); put("channelId", i.channelId); put("channelName", i.channelName)
        put("channelAvatarUrl", i.channelAvatarUrl); put("thumbnailUrl", i.thumbnailUrl); put("durationSec", i.durationSec)
        put("viewCount", i.viewCount); put("uploadedAtMs", i.uploadedAtMs); put("uploadedText", i.uploadedText)
        put("isLive", i.isLive); put("isUpcoming", i.isUpcoming); put("isShort", i.isShort)
    }

    companion object {
        /** A watch counts again after 30 minutes (plan task Y-7's rule). */
        const val REWATCH_AFTER_MS = 30 * 60_000L
        private const val HISTORY_CAP = 10_000
        private const val LIST_CAP = 5_000
        private const val PLAYLIST_CAP = 500
        private const val PLAYLIST_ITEM_CAP = 5_000
        private const val SAVED_CAP = 1_000
        private const val SEARCH_CAP = 50

        /**
         * A library already filled from [FakeVideoSource]'s samples, for rendering screens: both channels followed, a
         * history (one in progress, one watched, a Short), watch later, liked, a playlist, a saved playlist and searches.
         */
        fun withSamples(clock: () -> Long = { FakeVideoSource.NOW_MS }): FakeVideoLibrary = FakeVideoLibrary(clock).apply {
            FakeVideoSource.channels.forEach { subscribe(it) }
            val v = FakeVideoSource.videos
            recordWatch(v[4], positionMs = 400_000, durationMs = 482_000)     // watched (over 90 %)
            recordWatch(FakeVideoSource.shorts[0], positionMs = 9_000, durationMs = 17_000)
            recordWatch(v[2], positionMs = 300_000, durationMs = 1_325_000)   // in progress
            recordWatch(v[0], positionMs = 120_000, durationMs = 754_000)     // in progress, the most recent
            addToWatchLater(v[3]); addToWatchLater(v[5]); addToWatchLater(v[8])
            setLiked(v[1], true); setLiked(v[6], true)
            val mine = createPlaylist("For the drive")
            addToPlaylist(mine, v[3]); addToPlaylist(mine, v[0]); addToPlaylist(mine, v[9])
            savePlaylist(FakeVideoSource.playlist)
            addSearch("tape echo"); addSearch("rain sounds"); addSearch("synth build")
        }
    }
}
