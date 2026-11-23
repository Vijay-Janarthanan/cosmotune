package com.cosmotune.podio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The sleep timer by episodes (AntennaPod's "after N episodes"): the playback owner's tracker says each time the player moves on to
 * the next episode by itself, and when only the last one is left the platform's own "end of episode" timer takes over.
 */
object PodioSleepCount {
    private val _left = MutableStateFlow(0)
    /** The episodes still to finish, the playing one included; 0 when off. */
    val left: StateFlow<Int> = _left
    @Volatile private var arm: (() -> Unit)? = null

    /** Stops after [episodes] episodes; [endOfEpisode] is the platform's "end of episode" timer. */
    @Synchronized fun start(episodes: Int, endOfEpisode: () -> Unit) {
        if (episodes <= 1) { _left.value = 0; endOfEpisode(); return }
        arm = endOfEpisode
        _left.value = episodes
    }

    @Synchronized fun cancel() { _left.value = 0; arm = null }

    /** The player went on from one episode to the next. */
    @Synchronized fun episodeChanged() {
        val now = _left.value
        if (now <= 0) return
        if (now - 1 <= 1) { _left.value = 0; arm?.invoke(); arm = null } else _left.value = now - 1
    }
}
