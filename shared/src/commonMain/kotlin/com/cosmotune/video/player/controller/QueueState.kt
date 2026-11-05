package com.cosmotune.video.player.controller

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem

/**
 * The queue behind "Add to queue", a playlist's "Play all", Next and Previous, and autoplay's pick (spec 6.1). Immutable:
 * every operation returns the new state. [upNext] is what plays after the current video (what the watch page lists under
 * "Up next" and what `PlaybackUiState.queue` carries); [played] is what played before it in this session, oldest first, so
 * Previous can go back. The current video is in neither list.
 */
data class QueueState(val played: List<VideoItem> = emptyList(), val upNext: List<VideoItem> = emptyList()) {

    /** Where a step of the queue leads: the item to play now, and the queue after the step. */
    data class Step(val item: VideoItem, val queue: QueueState)

    /** Appends [item] to [upNext] ("Add to queue"); an item already queued is not queued twice. */
    fun add(item: VideoItem): QueueState = if (upNext.any { it.id == item.id }) this else copy(upNext = upNext + item)

    /** Next: the first of [upNext] becomes current and [current] moves to [played]; null when nothing is queued. */
    fun next(current: VideoItem?): Step? {
        val first = upNext.firstOrNull() ?: return null
        return Step(first, QueueState(played = pushed(current), upNext = upNext.drop(1)))
    }

    /** Previous: the last of [played] becomes current and [current] goes back to the front of [upNext]; null when nothing was played. */
    fun previous(current: VideoItem?): Step? {
        val last = played.lastOrNull() ?: return null
        val back = if (current == null) upNext else listOf(current) + upNext.filterNot { it.id == current.id }
        return Step(last, QueueState(played = played.dropLast(1), upNext = back))
    }

    /** [current] has been played: it joins [played] (the last [PLAYED_CAP], each video once), as when the user opens another video. */
    fun withPlayed(current: VideoItem?): QueueState = if (current == null) this else copy(played = pushed(current))

    /**
     * Autoplay's pick at the end of [current] (spec 6.1: "the first related video not watched"): the first video of [related]
     * that is not [current], not played earlier in this session ([played]; history can be paused, so the session's own trail
     * counts), not in [history] as watched (90 %, `HistoryEntry.isWatched`; a half-watched video is eligible), not a Short and
     * not a premiere that has not started. Channels and playlists are never picked.
     */
    fun autoplayPick(current: VideoItem?, related: List<ListItem>, history: List<HistoryEntry>): VideoItem? {
        val skip = HashSet<String>()
        for (entry in history) if (entry.isWatched) skip += entry.item.id
        for (item in played) skip += item.id
        if (current != null) skip += current.id
        for (candidate in related) {
            if (candidate !is ListItem.Video) continue
            val item = candidate.item
            if (item.id in skip || item.isShort || item.isUpcoming) continue
            return item
        }
        return null
    }

    private fun pushed(current: VideoItem?): List<VideoItem> =
        if (current == null) played else (played.filterNot { it.id == current.id } + current).takeLast(PLAYED_CAP)

    companion object {
        /** Previous goes back at most this far. */
        const val PLAYED_CAP = 50

        /**
         * A playlist's "Play all" (spec 5.6) starting at [startId]: the items before it are [played], the items after it
         * are [upNext]; an id that is not in [items] queues all of them. Each id counts once, at its first place.
         */
        fun playAll(items: List<VideoItem>, startId: String): QueueState {
            val unique = items.distinctBy { it.id }
            val at = unique.indexOfFirst { it.id == startId }
            if (at < 0) return QueueState(upNext = unique)
            return QueueState(played = unique.take(at).takeLast(PLAYED_CAP), upNext = unique.drop(at + 1))
        }
    }
}
