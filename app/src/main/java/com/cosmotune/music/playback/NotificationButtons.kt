package com.cosmotune.music.playback

import androidx.media3.common.Player

/**
 * The two buttons of Cosmotune's own on the phone's notification and lock screen, next to back, play and next. Android
 * (13 and later) draws at most two; a third only fits by giving up back or next. Like and Repeat, with Shuffle standing in
 * for Like when the song cannot be liked (signed out, a file on the phone, radio). An idea from LastWave Native.
 */
internal object NotificationButtons {
    enum class Kind { LIKE, SHUFFLE, REPEAT, NEXT_CHAPTER }

    /** A podcast episode with chapters has Next chapter where Repeat would be. */
    fun pick(canLike: Boolean, chapters: Boolean = false): List<Kind> = listOf(if (canLike) Kind.LIKE else Kind.SHUFFLE, if (chapters) Kind.NEXT_CHAPTER else Kind.REPEAT)

    /** Off → all → one → off: the same order as the app's own Repeat button. */
    fun nextRepeat(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /** What a tap on Repeat does next, read out by screen readers. */
    fun repeatLabel(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_OFF -> "Repeat all"
        Player.REPEAT_MODE_ALL -> "Repeat one"
        else -> "Repeat off"
    }
}
