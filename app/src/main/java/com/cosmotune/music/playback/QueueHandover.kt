package com.cosmotune.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Carries the live queue across when another player takes over from the session's.
 *
 * Two hand-overs load a second player with a copy of the queue and only later make it the session: a crossfade (the standby
 * is armed with the queue seconds before the blend's midpoint) and a version swap (the standby is prepared with the other cut
 * of the song, which can take seven seconds, then faded in). Until the hand-over the listener's edits — a row removed or
 * dragged, Play next, Add to queue, AutoPlay's top-up — land on the outgoing player, and the copy knew nothing of them, so they
 * were all lost at the swap. At the hand-over the incoming player takes the outgoing one's queue as it is *now*, around the
 * track it is already playing, which is never touched.
 */
internal object QueueHandover {

    /**
     * Where the incoming player's playing item stands in [live] (the outgoing player's queue, playing [liveCurrent]): the
     * first copy of it after the playing item, wrapping round to the start for repeat-all, or -1 when the listener has taken
     * it out of the queue. Matched by identity first, then by queue entry.
     */
    fun incomingIndex(live: List<MediaItem>, liveCurrent: Int, incoming: MediaItem): Int {
        val order = (liveCurrent + 1 until live.size) + (0 until liveCurrent.coerceAtMost(live.size))
        order.firstOrNull { live[it] === incoming }?.let { return it }
        val key = keyOf(incoming)
        return order.firstOrNull { keyOf(live[it]) == key } ?: -1
    }

    /**
     * A crossfade's hand-over: [into] (playing the song fading in) takes [out]'s live queue around that song. False, with
     * nothing changed, when the song fading in is no longer in [out]'s queue.
     */
    fun crossfade(out: Player, into: Player): Boolean {
        val incoming = into.currentMediaItem ?: return false
        val live = items(out)
        val at = incomingIndex(live, out.currentMediaItemIndex, incoming)
        if (at < 0) return false
        carry(into, live.subList(0, at), live.subList(at + 1, live.size))
        return true
    }

    /**
     * A version swap's hand-over: [standby] (playing the other cut of [active]'s song) takes [active]'s live queue, its own
     * item standing in for the one [active] is playing. False, with nothing changed, when [active] is playing nothing.
     */
    fun versionSwap(active: Player, standby: Player): Boolean {
        val live = items(active)
        val at = active.currentMediaItemIndex
        if (at !in live.indices || standby.currentMediaItem == null) return false
        carry(standby, live.subList(0, at), live.subList(at + 1, live.size))
        return true
    }

    /** [player]'s queue becomes [history], its playing item, [upcoming]; only what differs is replaced, the tail first. */
    fun carry(player: Player, history: List<MediaItem>, upcoming: List<MediaItem>) {
        val current = player.currentMediaItemIndex
        if (current !in 0 until player.mediaItemCount) return
        QueueShuffle.replaceChanged(player, current + 1, player.mediaItemCount, upcoming)
        QueueShuffle.replaceChanged(player, 0, current, history)
    }

    /** Whether [item] is still [playing], the item a hand-over started from (an edit elsewhere in the queue moves its index). */
    fun sameItem(item: MediaItem?, playing: MediaItem): Boolean =
        item != null && (item === playing || keyOf(item) == keyOf(playing))

    private fun keyOf(item: MediaItem): String = with(QueueShuffle) { item.key() }

    private fun items(player: Player): List<MediaItem> = List(player.mediaItemCount) { player.getMediaItemAt(it) }
}
