package com.cosmotune.video.ui.actions

import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PlaylistRef
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile

/** The mix the watch page is playing from, for the "Mix · …" line under Up next; null when none is. */
internal object MixSession {
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    fun show(title: String) { _title.value = title }

    fun clear(title: String) { _title.compareAndSet(title, null) }
}

/**
 * Plays a suggested mix as a mix: its first video is already playing, so its other videos are added to the queue in the mix's order
 * (Up next, Next and autoplay walk them) and, as a mix has no end, more are asked for from its last video whenever fewer than
 * [LOW_WATER] are left. It stops when something outside the mix plays or the site has nothing more.
 */
internal class MixFollower(private val account: VideoAccount, private val mix: PlaylistRef, private val playback: VideoPlayback) {
    private val ids = LinkedHashSet<String>()
    private var exhausted = false

    /** Queues the mix's first stretch; false when the site gave nothing (the first video then plays alone). */
    suspend fun start(): Boolean {
        val first = mix.firstVideoId ?: return false
        ids += first
        return extend(first)
    }

    /** Follows the player: tops the queue up while the mix plays; false once something else plays. */
    suspend fun onState(state: PlaybackUiState): Boolean {
        val current = state.item?.id ?: return true
        if (current !in ids) return false
        if (!exhausted && state.queue.size < LOW_WATER) exhausted = !extend(ids.last())
        return true
    }

    private suspend fun extend(from: String): Boolean {
        val stretch = account.mix(mix.id, from).getOrNull() ?: return false
        var added = false
        for (video in stretch) {
            if (video.isShort || !ids.add(video.id)) continue
            playback.addToQueue(video)
            added = true
        }
        return added
    }
}

/** A mix tapped on Home ([title] is its card's): queue it, name it under Up next, and keep it going for as long as it plays. */
internal suspend fun playMix(account: VideoAccount, mix: PlaylistRef, playback: VideoPlayback, title: String) {
    val follower = MixFollower(account, mix, playback)
    if (!follower.start()) return
    MixSession.show(title)
    try {
        playback.state.takeWhile { follower.onState(it) }.collect { }
    } finally {
        MixSession.clear(title)
    }
}

internal const val LOW_WATER = 5
