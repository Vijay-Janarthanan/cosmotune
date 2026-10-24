package com.cosmotune.music.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The progress ring's playhead: run on from the phone's last reading while it moves. */
class NowPlayingProgressTest {

    private fun np(
        positionMs: Long = 30_000L,
        positionAt: Long = 1_000_000L,
        durationMs: Long = 200_000L,
        advancing: Boolean = true,
        speed: Float = 1f,
    ) = NowPlaying(
        mediaId = "a", title = "t", artist = "r", album = "b",
        playing = advancing, advancing = advancing,
        positionMs = positionMs, positionAt = positionAt, durationMs = durationMs, speed = speed,
        hasPrevious = true, hasNext = true, liked = false, canLike = true, shuffle = false, repeatMode = 0,
        colors = emptyList(), artId = null,
    )

    @Test
    fun `a moving playhead runs on at the phone's speed`() {
        assertEquals(40_000L, np().positionAt(1_010_000L))
        assertEquals(45_000L, np(speed = 1.5f).positionAt(1_010_000L))
        assertEquals(0.2f, np().progressAt(1_010_000L), 1e-6f)
    }

    @Test
    fun `a paused or buffering playhead stays where the phone left it`() {
        assertEquals(30_000L, np(advancing = false).positionAt(1_090_000L))
    }

    @Test
    fun `never past the end, never back before the reading`() {
        assertEquals(200_000L, np().positionAt(1_900_000L))
        assertEquals(1f, np().progressAt(1_900_000L), 0f)
        // A watch clock a little behind the phone's reading does not rewind the ring.
        assertEquals(30_000L, np().positionAt(990_000L))
    }

    @Test
    fun `an unknown duration draws an empty ring`() {
        assertEquals(0f, np(durationMs = 0L).progressAt(1_010_000L), 0f)
        assertEquals(30_000L, np(durationMs = -1L).positionAt(1_010_000L))
    }

    @Test
    fun `the volume arc is the phone's level over its range`() {
        assertEquals(0.5f, Volume(level = 7, max = 14).fraction, 1e-6f)
        assertEquals(0f, Volume(level = 3, max = 0).fraction, 0f)
    }
}
