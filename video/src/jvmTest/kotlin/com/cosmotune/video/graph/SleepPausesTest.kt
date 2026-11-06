package com.cosmotune.video.graph

import com.cosmotune.video.player.PlayerStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepPausesTest {
    @Test
    fun onlyALoadedVideoIsPausedByTheSleepTimer() {
        // mpv keeps its pause between files: pausing an idle player would start the next video paused.
        assertFalse(sleepPauses(PlayerStatus.IDLE))
        assertFalse(sleepPauses(PlayerStatus.ENDED))
        assertTrue(sleepPauses(PlayerStatus.READY))
        assertTrue(sleepPauses(PlayerStatus.BUFFERING))
        assertTrue(sleepPauses(PlayerStatus.LOADING))
    }
}
