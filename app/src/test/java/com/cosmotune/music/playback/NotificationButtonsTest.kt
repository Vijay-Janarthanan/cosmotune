package com.cosmotune.music.playback

import androidx.media3.common.Player
import com.cosmotune.music.playback.NotificationButtons.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The notification always has two buttons of its own: Like and Repeat, or Shuffle and Repeat. */
class NotificationButtonsTest {
    @Test
    fun likeAndRepeatWhenTheSongCanBeLiked() {
        assertEquals(listOf(Kind.LIKE, Kind.REPEAT), NotificationButtons.pick(canLike = true))
    }

    @Test
    fun shuffleStandsInForLikeWhenItCannotBeUsed() {
        assertEquals(listOf(Kind.SHUFFLE, Kind.REPEAT), NotificationButtons.pick(canLike = false))
    }

    @Test
    fun repeatGoesOffAllOneOffAsInTheApp() {
        val seen = generateSequence(Player.REPEAT_MODE_OFF) { NotificationButtons.nextRepeat(it) }.take(4).toList()
        assertEquals(listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF), seen)
    }
}
