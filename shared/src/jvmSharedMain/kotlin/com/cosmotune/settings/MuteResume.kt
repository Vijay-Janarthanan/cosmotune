package com.cosmotune.settings

import kotlin.math.ceil

/**
 * Pause on mute's memory: a song paused because the volume reached zero plays on when the volume comes back up, at a level
 * that can be heard. Only that song, only while it is still paused, and only if the listener has not played or paused
 * anything themselves in between: then the pause is theirs. An idea from LastWave Native.
 */
class MuteResume {
    private var pausedSongId: String? = null

    /** The volume reached zero and [songId], which was playing, was paused for it. */
    fun pausedForMute(songId: String?) {
        pausedSongId = songId
    }

    /** The listener played or paused something themselves: a later unmute leaves it be. */
    fun manual() {
        pausedSongId = null
    }

    /** The volume came back up from zero: whether [songId], [stillPaused], should play on. Asked once per unmute. */
    fun unmuted(songId: String?, stillPaused: Boolean): Boolean {
        val resume = songId != null && songId == pausedSongId && stillPaused
        pausedSongId = null
        return resume
    }

    companion object {
        /** The least share of the full volume a song plays on at, so it does not come back all but silent. */
        const val FLOOR = 0.10f

        /** The volume step to play on at, on a stream of [max] steps that came back up at [level]. */
        fun resumeLevel(level: Int, max: Int): Int =
            if (max <= 0) level else maxOf(level, ceil(max * FLOOR.toDouble()).toInt().coerceIn(1, max))
    }
}
