package com.cosmotune.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Repeat this song" a set number of times (from the player's More menu): the song plays [Hold.left] more times, then the
 * queue carries on as before. Choosing another song, or Off, ends it. Shared by the phone's service and the desktop's engine.
 */
object RepeatTimes {
    data class Hold(val videoId: String, val left: Int) {
        /** "2 more times", for the player's chip and menu. */
        val line: String get() = if (left == 1) "1 more time" else "$left more times"
    }

    /** The plays a listener can pick: each is the total, this play included. */
    val CHOICES = listOf(2, 3, 5, 10)

    private val holdFlow = MutableStateFlow<Hold?>(null)
    val hold: StateFlow<Hold?> = holdFlow.asStateFlow()

    /** Plays [videoId] [plays] times in all, counting the play under way. */
    fun set(videoId: String, plays: Int) {
        holdFlow.value = if (videoId.isBlank() || plays < 2) null else Hold(videoId, plays - 1)
    }

    fun clear() { holdFlow.value = null }

    /** The song ended: true (and one play used) when it should play again rather than move on. */
    fun consume(videoId: String?): Boolean {
        val current = holdFlow.value ?: return false
        if (current.videoId != videoId) { holdFlow.value = null; return false }
        holdFlow.value = if (current.left > 1) current.copy(left = current.left - 1) else null
        return true
    }

    /** Another song started: a hold for a different song ends. */
    fun songChanged(videoId: String?) {
        val current = holdFlow.value ?: return
        if (current.videoId != videoId) holdFlow.value = null
    }
}
