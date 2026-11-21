package com.cosmotune.extras

import com.cosmotune.settings.MuteResume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A song paused because the volume reached zero plays on when it comes back up, and only then. */
class MuteResumeTest {
    @Test
    fun theSongPausedForTheMutePlaysOnWhenTheVolumeComesBack() {
        val memory = MuteResume()
        memory.pausedForMute("song")
        assertTrue(memory.unmuted("song", stillPaused = true))
        // Once: a second unmute is not another reason to play.
        assertFalse(memory.unmuted("song", stillPaused = true))
    }

    @Test
    fun aPauseOrPlayOfTheListenersOwnMakesTheSongTheirs() {
        val memory = MuteResume()
        memory.pausedForMute("song")
        memory.manual()
        assertFalse(memory.unmuted("song", stillPaused = true))
    }

    @Test
    fun anotherSongOrOneAlreadyPlayingIsLeftAlone() {
        val memory = MuteResume()
        memory.pausedForMute("song")
        assertFalse(memory.unmuted("next", stillPaused = true))
        memory.pausedForMute("song")
        assertFalse(memory.unmuted("song", stillPaused = false))
        // Nothing was paused for a mute at all.
        assertFalse(MuteResume().unmuted("song", stillPaused = true))
        assertFalse(MuteResume().also { it.pausedForMute(null) }.unmuted(null, stillPaused = true))
    }

    @Test
    fun itPlaysOnLoudEnoughToBeHeard() {
        // A phone's 15 steps: one step up is raised to two (10 % rounded up); more is left as it is.
        assertEquals(2, MuteResume.resumeLevel(1, 15))
        assertEquals(5, MuteResume.resumeLevel(5, 15))
        assertEquals(1, MuteResume.resumeLevel(1, 5))
        assertEquals(3, MuteResume.resumeLevel(3, 0))
    }
}
