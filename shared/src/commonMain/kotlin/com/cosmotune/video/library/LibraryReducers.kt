package com.cosmotune.video.library

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem

/** What a merge made of two lists (spec 8.2): the merged [list], how many records it [added] or changed, and how many it [skipped]. */
data class MergeResult<T>(val list: List<T>, val added: Int, val skipped: Int)

/**
 * The rules of the library (plan task Y-7, step 1; spec 8.1) as pure functions over immutable lists: no clock, no random
 * numbers, no files. The store passes the time and the ids in. Every function returns the list it was given, the same
 * instance, when the call changes nothing, so a caller can tell cheaply whether there is anything to write.
 */
object LibraryReducers {
    /** History keeps 10,000 entries; the oldest is dropped (spec 8.1). */
    const val HISTORY_CAP = 10_000

    /** Watch later and liked keep 5,000 items each (spec 8.1). */
    const val LIST_CAP = 5_000

    /** At most 500 playlists of your own (spec 8.1). */
    const val PLAYLIST_CAP = 500

    /** At most 5,000 items in each of your playlists (spec 8.1). */
    const val PLAYLIST_ITEM_CAP = 5_000

    /** At most 1,000 saved remote playlists (spec 8.1). */
    const val SAVED_CAP = 1_000

    /** The last 50 searches (spec 8.1). */
    const val SEARCH_CAP = 50

    /** A video counts as watched again when its previous watch was more than 30 minutes ago. */
    const val REWATCH_AFTER_MS = 30 * 60_000L

    // ------------------------------------------------------------------ subscriptions

    /**
     * Follows [channel] at [nowMs]. A channel already followed keeps its date and last-seen upload; the newer name wins, and so
     * does the newer avatar unless it is missing.
     */
    fun subscribe(list: List<Subscription>, channel: ChannelItem, nowMs: Long): List<Subscription> {
        if (channel.id.isBlank()) return list
        val index = list.indexOfFirst { it.channelId == channel.id }
        if (index < 0) return list + Subscription(channel.id, channel.name, channel.avatarUrl, nowMs, null)
        val old = list[index]
        val updated = old.copy(name = channel.name, avatarUrl = channel.avatarUrl ?: old.avatarUrl)
        return if (updated == old) list else list.replacedAt(index, updated)
    }

    /** Stops following [channelId]. */
    fun unsubscribe(list: List<Subscription>, channelId: String): List<Subscription> =
        if (list.none { it.channelId == channelId }) list else list.filterNot { it.channelId == channelId }

    // ------------------------------------------------------------------ history

    /**
     * Records that [item] was watched up to [positionMs] of [durationMs] at [nowMs]: one entry per video, moved to the front,
     * position and duration updated; the watch count rises only when the previous watch was more than
     * [REWATCH_AFTER_MS] ago (the controller reports every 10 s, and those are one watch). The 10,000 cap drops the oldest.
     */
    fun recordWatch(list: List<HistoryEntry>, item: VideoItem, positionMs: Long, durationMs: Long, nowMs: Long): List<HistoryEntry> {
        if (item.id.isBlank()) return list
        val previous = list.firstOrNull { it.item.id == item.id }
        val count = when {
            previous == null -> 1
            nowMs - previous.lastWatchedMs > REWATCH_AFTER_MS -> previous.watchCount + 1
            else -> previous.watchCount
        }
        val entry = HistoryEntry(item, nowMs, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), count)
        val rest = if (previous == null) list else list.filterNot { it.item.id == item.id }
        return buildList(minOf(rest.size + 1, HISTORY_CAP)) {
            add(entry)
            for (e in rest) {
                if (size >= HISTORY_CAP) break
                add(e)
            }
        }
    }

    /** Removes [videoId] from the history. */
    fun removeFromHistory(list: List<HistoryEntry>, videoId: String): List<HistoryEntry> =
        if (list.none { it.item.id == videoId }) list else list.filterNot { it.item.id == videoId }

    // ------------------------------------------------------------------ watch later and liked

    /** Watch later: appends [item] unless it is there already or the list is full (5,000). */
    fun addToList(list: List<VideoItem>, item: VideoItem): List<VideoItem> =
        if (item.id.isBlank() || list.size >= LIST_CAP || list.any { it.id == item.id }) list else list + item

    /** Removes [videoId] from an item list. */
    fun removeFromList(list: List<VideoItem>, videoId: String): List<VideoItem> =
        if (list.none { it.id == videoId }) list else list.filterNot { it.id == videoId }

    /** Moves the element at [from] to index [to]; an index out of range, or the same place, changes nothing. */
    fun <T> moveInList(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val result = list.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /** Liked: puts [item] at the front (a repeat moves it there, no duplicate); past 5,000 the oldest goes. */
    fun like(list: List<VideoItem>, item: VideoItem): List<VideoItem> {
        if (item.id.isBlank() || list.firstOrNull() == item) return list
        return (listOf(item) + list.filterNot { it.id == item.id }).take(LIST_CAP)
    }

    // ------------------------------------------------------------------ your playlists

    /** Adds an empty playlist [id] named [name] (trimmed); nothing past 500 playlists, or when [id] exists. */
    fun createPlaylist(list: List<LocalPlaylist>, id: String, name: String, nowMs: Long): List<LocalPlaylist> =
        if (id.isBlank() || list.size >= PLAYLIST_CAP || list.any { it.id == id }) list
        else list + LocalPlaylist(id, name.trim(), emptyList(), nowMs, nowMs)

    /** Renames playlist [id]; a blank name is ignored. */
    fun renamePlaylist(list: List<LocalPlaylist>, id: String, name: String, nowMs: Long): List<LocalPlaylist> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return list
        return editPlaylist(list, id) { if (it.name == trimmed) it else it.copy(name = trimmed, updatedMs = nowMs) }
    }

    /** Deletes playlist [id]. */
    fun deletePlaylist(list: List<LocalPlaylist>, id: String): List<LocalPlaylist> =
        if (list.none { it.id == id }) list else list.filterNot { it.id == id }

    /** Appends [item] to playlist [id] unless it is there already or the playlist is full (5,000). */
    fun addToPlaylist(list: List<LocalPlaylist>, id: String, item: VideoItem, nowMs: Long): List<LocalPlaylist> =
        editPlaylist(list, id) { p ->
            if (item.id.isBlank() || p.items.size >= PLAYLIST_ITEM_CAP || p.items.any { it.id == item.id }) p
            else p.copy(items = p.items + item, updatedMs = nowMs)
        }

    /** Removes [videoId] from playlist [id]. */
    fun removeFromPlaylist(list: List<LocalPlaylist>, id: String, videoId: String, nowMs: Long): List<LocalPlaylist> =
        editPlaylist(list, id) { p ->
            if (p.items.none { it.id == videoId }) p else p.copy(items = p.items.filterNot { it.id == videoId }, updatedMs = nowMs)
        }

    /** Moves the item at [from] to [to] in playlist [id]. */
    fun moveInPlaylist(list: List<LocalPlaylist>, id: String, from: Int, to: Int, nowMs: Long): List<LocalPlaylist> =
        editPlaylist(list, id) { p ->
            val moved = moveInList(p.items, from, to)
            if (moved === p.items) p else p.copy(items = moved, updatedMs = nowMs)
        }

    private fun editPlaylist(list: List<LocalPlaylist>, id: String, edit: (LocalPlaylist) -> LocalPlaylist): List<LocalPlaylist> {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return list
        val old = list[index]
        val updated = edit(old)
        return if (updated === old || updated == old) list else list.replacedAt(index, updated)
    }

    // ------------------------------------------------------------------ saved playlists

    /** Saves [item] at the front (a repeat replaces the older copy); past 1,000 the oldest goes. */
    fun savePlaylist(list: List<PlaylistItem>, item: PlaylistItem): List<PlaylistItem> {
        if (item.id.isBlank() || list.firstOrNull() == item) return list
        return (listOf(item) + list.filterNot { it.id == item.id }).take(SAVED_CAP)
    }

    /** Forgets the saved playlist [id]. */
    fun unsavePlaylist(list: List<PlaylistItem>, id: String): List<PlaylistItem> =
        if (list.none { it.id == id }) list else list.filterNot { it.id == id }

    // ------------------------------------------------------------------ searches

    /** Recent searches: [query] (trimmed) goes to the front, an older copy in any case is dropped, 50 are kept; a blank one is ignored. */
    fun addSearch(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty() || list.firstOrNull() == q) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(SEARCH_CAP)
    }

    /** Removes [query] (any case) from the recent searches. */
    fun removeSearch(list: List<String>, query: String): List<String> =
        if (list.none { it.equals(query, ignoreCase = true) }) list else list.filterNot { it.equals(query, ignoreCase = true) }

    // ------------------------------------------------------------------ merging an import (spec 8.1, 8.2: never deletes)

    /** Union by channel id: what I have stays as it is; the channels I did not follow are appended. A blank id is skipped. */
    fun mergeSubscriptions(mine: List<Subscription>, incoming: List<Subscription>): MergeResult<Subscription> {
        val known = HashSet<String>()
        mine.forEach { known.add(it.channelId) }
        val fresh = ArrayList<Subscription>()
        var skipped = 0
        for (s in incoming) {
            when {
                s.channelId.isBlank() -> skipped += 1
                known.add(s.channelId) -> fresh += s
            }
        }
        return MergeResult(if (fresh.isEmpty()) mine else mine + fresh, fresh.size, skipped)
    }

    /**
     * Union by video id; for a video in both, the newer watch wins with its position. A newer watch that carries no position
     * (a Takeout entry: only the time) leaves my position, duration and item alone and only moves the time forward. The
     * watch count is the higher of the two. The result is most recent first and capped at 10,000: new entries that do not fit
     * count as skipped.
     */
    fun mergeHistory(mine: List<HistoryEntry>, incoming: List<HistoryEntry>): MergeResult<HistoryEntry> {
        val current = HashMap<String, HistoryEntry>()
        mine.forEach { current[it.item.id] = it }
        val fresh = LinkedHashSet<String>()          // ids that were not in mine, in the order they came
        val touched = HashSet<String>()              // ids added or changed
        var skipped = 0
        for (entry in incoming) {
            val id = entry.item.id
            if (id.isBlank()) {
                skipped += 1
                continue
            }
            val old = current[id]
            val merged = if (old == null) entry else mergeEntry(old, entry)
            if (merged == old) continue
            if (old == null) fresh.add(id)
            current[id] = merged
            touched.add(id)
        }
        if (touched.isEmpty()) return MergeResult(mine, 0, skipped)
        val combined = ArrayList<HistoryEntry>(current.size)
        mine.mapTo(combined) { current.getValue(it.item.id) }
        fresh.mapTo(combined) { current.getValue(it) }
        val sorted = combined.sortedByDescending { it.lastWatchedMs }
        val kept = sorted.take(HISTORY_CAP)
        val droppedNew = sorted.drop(HISTORY_CAP).count { it.item.id in touched }
        return MergeResult(kept, touched.size - droppedNew, skipped + droppedNew)
    }

    private fun mergeEntry(mine: HistoryEntry, theirs: HistoryEntry): HistoryEntry {
        if (theirs.lastWatchedMs <= mine.lastWatchedMs) return mine
        val count = maxOf(mine.watchCount, theirs.watchCount)
        return if (theirs.positionMs <= 0 && theirs.durationMs <= 0) mine.copy(lastWatchedMs = theirs.lastWatchedMs, watchCount = count)
        else theirs.copy(watchCount = count)
    }

    /**
     * Playlists merge by id first (the same playlist, even if it was renamed here), then by name (trimmed, any case): the items
     * I do not have are appended, without duplicates, up to 5,000, and the updated time becomes [nowMs]. A playlist that
     * matches nothing is added as it is, up to 500 (the rest are skipped). The count is of playlists added or grown.
     */
    fun mergePlaylists(mine: List<LocalPlaylist>, incoming: List<LocalPlaylist>, nowMs: Long): MergeResult<LocalPlaylist> {
        val result = mine.toMutableList()
        val counted = HashSet<String>()
        var changed = false
        var skipped = 0
        for (p in incoming) {
            if (p.id.isBlank()) {
                skipped += 1
                continue
            }
            val key = p.name.trim().lowercase()
            var index = result.indexOfFirst { it.id == p.id }
            if (index < 0 && key.isNotEmpty()) index = result.indexOfFirst { it.name.trim().lowercase() == key }
            if (index < 0) {
                if (result.size >= PLAYLIST_CAP) {
                    skipped += 1
                    continue
                }
                val items = p.items.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(PLAYLIST_ITEM_CAP)
                result += p.copy(name = p.name.trim(), items = items)
                counted.add(p.id)
                changed = true
                continue
            }
            val existing = result[index]
            val have = HashSet<String>()
            existing.items.forEach { have.add(it.id) }
            val fresh = p.items.filter { it.id.isNotBlank() && have.add(it.id) }
            val room = (PLAYLIST_ITEM_CAP - existing.items.size).coerceAtLeast(0)
            val toAdd = fresh.take(room)
            skipped += fresh.size - toAdd.size
            if (toAdd.isEmpty()) continue
            result[index] = existing.copy(items = existing.items + toAdd, updatedMs = nowMs)
            counted.add(existing.id)
            changed = true
        }
        return MergeResult(if (changed) result else mine, counted.size, skipped)
    }

    /** Watch later and liked: union by video id, the ones I lack appended up to 5,000 (the rest skipped); a blank id is skipped. */
    fun mergeItems(mine: List<VideoItem>, incoming: List<VideoItem>): MergeResult<VideoItem> {
        val known = HashSet<String>()
        mine.forEach { known.add(it.id) }
        val fresh = ArrayList<VideoItem>()
        var skipped = 0
        for (item in incoming) {
            when {
                item.id.isBlank() -> skipped += 1
                item.id in known -> Unit
                mine.size + fresh.size >= LIST_CAP -> skipped += 1
                else -> {
                    known.add(item.id)
                    fresh += item
                }
            }
        }
        return MergeResult(if (fresh.isEmpty()) mine else mine + fresh, fresh.size, skipped)
    }

    /** Saved playlists: union by id, appended after mine, up to 1,000 (the rest skipped). */
    fun mergeSavedPlaylists(mine: List<PlaylistItem>, incoming: List<PlaylistItem>): MergeResult<PlaylistItem> {
        val known = HashSet<String>()
        mine.forEach { known.add(it.id) }
        val fresh = ArrayList<PlaylistItem>()
        var skipped = 0
        for (item in incoming) {
            when {
                item.id.isBlank() -> skipped += 1
                item.id in known -> Unit
                mine.size + fresh.size >= SAVED_CAP -> skipped += 1
                else -> {
                    known.add(item.id)
                    fresh += item
                }
            }
        }
        return MergeResult(if (fresh.isEmpty()) mine else mine + fresh, fresh.size, skipped)
    }

    /** Recent searches: the ones I lack (any case) appended after mine, up to 50 (the rest skipped); a blank one is ignored. */
    fun mergeSearches(mine: List<String>, incoming: List<String>): MergeResult<String> {
        val known = HashSet<String>()
        mine.forEach { known.add(it.lowercase()) }
        val fresh = ArrayList<String>()
        var skipped = 0
        for (raw in incoming) {
            val q = raw.trim()
            when {
                q.isEmpty() -> Unit
                !known.add(q.lowercase()) -> Unit
                mine.size + fresh.size >= SEARCH_CAP -> skipped += 1
                else -> fresh += q
            }
        }
        return MergeResult(if (fresh.isEmpty()) mine else mine + fresh, fresh.size, skipped)
    }

    private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
}
