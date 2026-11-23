package com.cosmotune.music.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaConstants

/**
 * The two buttons of Cosmotune's own on the phone's notification and lock screen, next to back, play and next. Android
 * (13 and later) draws at most two; a third only fits by giving up back or next. Like and Repeat, with Shuffle standing in
 * for Like when the song cannot be liked (signed out, a file on the phone, radio). An idea from LastWave Native.
 */
internal object NotificationButtons {
    enum class Kind { LIKE, SHUFFLE, REPEAT }

    /**
     * The session extras that keep Back and Next in their places while there is nowhere to go. Without them Media3 moves the first
     * of these buttons (Like) into Next's slot on the last song of a queue or a single song opened from a link, and a panel that
     * keeps its button positions turned a tap on Next into a Like. With them an unavailable Back or Next leaves its slot empty.
     * From upstream 1.10 (GPL-3.0).
     */
    val reservedSlots = listOf(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT)

    fun pick(canLike: Boolean): List<Kind> = listOf(if (canLike) Kind.LIKE else Kind.SHUFFLE, Kind.REPEAT)

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
