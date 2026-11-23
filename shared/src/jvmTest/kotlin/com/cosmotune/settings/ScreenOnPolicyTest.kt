package com.cosmotune.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenOnPolicyTest {
    @Test fun theDefaultKeepsTheScreenOnOnlyForLyricsOrAVideo() {
        val policy = ScreenOnPolicy.DEFAULT
        assertTrue(policy.keepsOn(playerOpen = true, lyricsOrVideo = true, isPlaying = false))
        assertFalse(policy.keepsOn(playerOpen = true, lyricsOrVideo = false, isPlaying = true))
        assertFalse(policy.keepsOn(playerOpen = false, lyricsOrVideo = false, isPlaying = true))
    }

    @Test fun playerOpenAddsAPlayingSongWhileThePlayerShows() {
        val policy = ScreenOnPolicy.PLAYER_OPEN
        assertTrue(policy.keepsOn(playerOpen = true, lyricsOrVideo = false, isPlaying = true))
        assertFalse(policy.keepsOn(playerOpen = true, lyricsOrVideo = false, isPlaying = false), "paused, it may sleep")
        assertFalse(policy.keepsOn(playerOpen = false, lyricsOrVideo = false, isPlaying = true))
    }

    @Test fun wheneverMusicPlaysHoldsItAnywhere() {
        val policy = ScreenOnPolicy.WHILE_PLAYING
        assertTrue(policy.keepsOn(playerOpen = false, lyricsOrVideo = false, isPlaying = true))
        assertFalse(policy.keepsOn(playerOpen = false, lyricsOrVideo = false, isPlaying = false))
        assertTrue(policy.keepsOn(playerOpen = true, lyricsOrVideo = true, isPlaying = false))
    }

    @Test fun anUnknownStoredNameFallsBackToTheDefault() {
        assertEquals(ScreenOnPolicy.LYRICS_OR_VIDEO, ScreenOnPolicy.named("gone"))
        assertEquals(ScreenOnPolicy.WHILE_PLAYING, ScreenOnPolicy.named("WHILE_PLAYING"))
    }
}
