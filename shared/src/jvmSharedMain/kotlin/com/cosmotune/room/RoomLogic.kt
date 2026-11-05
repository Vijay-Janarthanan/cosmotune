package com.cosmotune.room

import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack

/**
 * The rules for what each control does to the shared playback and queue, as plain functions so they can be tested without a network.
 * They are the same rules the party server applied: a position is stored with the server time it was true at, a resume is scheduled
 * a moment ahead so every device aims at one instant, and the upcoming queue is capped.
 */
object RoomLogic {
    /** How far ahead a resume or seek is scheduled, so every device has time to buffer and start together. */
    const val PLAY_LEAD_MS = 350L

    /** The playing track and 25 more after it. */
    const val MAX_QUEUE = 26

    /** Pressing Previous after this long into a song restarts it instead of going back. */
    const val RESTART_THRESHOLD_MS = 3_000L

    /** Where the party is at a server time. Before the scheduled anchor it holds still; while paused it does not move. */
    fun positionAt(playback: PartyPlayback, serverNowMs: Long): Long =
        if (playback.isPlaying) playback.positionMs + maxOf(0L, serverNowMs - playback.anchorMs) else playback.positionMs

    class Result(val playback: PartyPlayback, val queue: PartyQueue?)

    /** The state after [control], or null when the control changes nothing (such as Next at the end of the queue). */
    fun reduce(playback: PartyPlayback, queue: PartyQueue, control: RoomControl, serverNowMs: Long, byUid: String, byName: String): Result? {
        val position = positionAt(playback, serverNowMs)
        fun moved(p: PartyPlayback) = p.copy(seq = playback.seq + 1, updatedBy = byUid)
        fun resumed(positionMs: Long, playing: Boolean) = moved(
            playback.copy(isPlaying = playing, positionMs = positionMs, anchorMs = if (playing) serverNowMs + PLAY_LEAD_MS else serverNowMs),
        )
        fun withQueue(newQueue: PartyQueue, p: PartyPlayback) = Result(
            moved(p.copy(queueSeq = newQueue.seq, queueIndex = newQueue.index, queueLength = newQueue.items.size)),
            newQueue,
        )
        fun startTrack(track: PartyTrack, index: Int, newQueue: PartyQueue): Result = withQueue(
            newQueue.copy(index = index),
            playback.copy(track = track, isPlaying = true, positionMs = 0, anchorMs = serverNowMs + PLAY_LEAD_MS, startedBy = byUid, startedByName = byName),
        )

        return when (control) {
            is RoomControl.Play -> Result(resumed(control.positionMs ?: position, true), null)
            is RoomControl.Pause -> Result(resumed(control.positionMs ?: position, false), null)
            // a live station has no position to move to
            is RoomControl.Seek -> if (playback.track?.isLive == true) null else Result(resumed(control.positionMs.coerceAtLeast(0), playback.isPlaying), null)

            is RoomControl.SetTrack -> {
                val items = queue.items
                // the copy nearest after the current place, then any copy; a track not in the queue goes in right after the current one
                val existing = items.indices.firstOrNull { it > queue.index && items[it].videoId == control.track.videoId }
                    ?: items.indexOfFirst { it.videoId == control.track.videoId }.takeIf { it >= 0 }
                val (list, index) = if (existing != null) items to existing else {
                    val at = (queue.index + 1).coerceIn(0, items.size)
                    items.toMutableList().apply { add(at, control.track.copy(fromAutoplay = false)) }.let { it to at }
                }
                val bounded = bound(list, index)
                val started = startTrack(control.track, bounded.second, queue.copy(seq = queue.seq + 1, items = bounded.first))
                Result(
                    started.playback.copy(isPlaying = control.isPlaying, positionMs = control.positionMs, anchorMs = if (control.isPlaying) serverNowMs + PLAY_LEAD_MS else serverNowMs),
                    started.queue,
                )
            }

            is RoomControl.SetQueue -> {
                if (control.items.isEmpty()) return null
                val (list, index) = bound(control.items, control.index.coerceIn(0, control.items.lastIndex))
                startTrack(list[index], index, PartyQueue(queue.seq + 1, index, list))
            }

            is RoomControl.QueueAdd -> {
                val waiting = queue.items.drop(queue.index + 1).filter { it.fromAutoplay }.map { it.videoId }.toSet()
                // a suggestion already waiting is not queued twice; what people queue by hand always is
                val fresh = control.tracks.filter { !(it.fromAutoplay && it.videoId in waiting) }.distinctBy { if (it.fromAutoplay) it.videoId else Any() }
                if (fresh.isEmpty()) return null
                val at = if (control.playNext) (queue.index + 1).coerceIn(0, queue.items.size) else queue.items.size
                val list = queue.items.toMutableList().apply { addAll(at, fresh) }
                val bounded = bound(list, queue.index)
                if (bounded.first.size == queue.items.size) return null
                withQueue(queue.copy(seq = queue.seq + 1, items = bounded.first, index = bounded.second), playback)
            }

            is RoomControl.QueueRemove -> {
                val at = queue.items.indices.firstOrNull { it > queue.index && queue.items[it].videoId == control.videoId } ?: return null
                withQueue(queue.copy(seq = queue.seq + 1, items = queue.items.filterIndexed { i, _ -> i != at }), playback)
            }

            RoomControl.QueueClear -> {
                val keep = queue.items.take(queue.index + 1)
                if (keep.size == queue.items.size) return null
                withQueue(queue.copy(seq = queue.seq + 1, items = keep), playback)
            }

            is RoomControl.QueueMove -> {
                // the track's id wins over the index, which may have shifted while the request was in flight
                val from = control.videoId?.let { id -> queue.items.indices.firstOrNull { queue.items[it].videoId == id && it > queue.index } } ?: control.fromIndex
                if (from <= queue.index || from !in queue.items.indices) return null
                val to = control.toIndex.coerceIn(queue.index + 1, queue.items.lastIndex)
                if (from == to) return null
                val list = queue.items.toMutableList().apply { add(to, removeAt(from)) }
                withQueue(queue.copy(seq = queue.seq + 1, items = list), playback)
            }

            is RoomControl.NextAfter -> {
                if (playback.seq != control.expectedSeq) return null
                reduce(playback, queue, RoomControl.Next, serverNowMs, byUid, byName)
            }

            RoomControl.Next -> {
                val next = queue.index + 1
                if (next !in queue.items.indices) return null
                startTrack(queue.items[next], next, queue.copy(seq = queue.seq + 1))
            }

            RoomControl.Previous -> {
                val previous = queue.index - 1
                if (position > RESTART_THRESHOLD_MS || previous !in queue.items.indices) Result(resumed(0, playback.isPlaying), null)
                else startTrack(queue.items[previous], previous, queue.copy(seq = queue.seq + 1))
            }
        }
    }

    /** Keeps the queue to [MAX_QUEUE] tracks, dropping the oldest history first so what is playing and what is next survive. */
    fun bound(items: List<PartyTrack>, index: Int): Pair<List<PartyTrack>, Int> {
        if (items.size <= MAX_QUEUE) return items to index
        val dropFront = (items.size - MAX_QUEUE).coerceAtMost(index)
        return items.drop(dropFront).take(MAX_QUEUE) to (index - dropFront)
    }
}
