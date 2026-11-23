package com.cosmotune.music.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** The sleep timer's fade: full volume until its last 30 seconds, then down in a straight line to silence at the deadline. */
class SleepFadeTest {
    @Test
    fun fullVolumeOutsideTheLastThirtySecondsOrWithTheFadeOff() {
        assertEquals(1f, SleepFade.gain(45_000L, enabled = true))
        assertEquals(1f, SleepFade.gain(30_000L, enabled = true))
        assertEquals(1f, SleepFade.gain(15_000L, enabled = false))
        assertEquals(1f, SleepFade.gain(null, enabled = true))
    }

    @Test
    fun theVolumeFallsInAStraightLineToSilence() {
        assertEquals(0.5f, SleepFade.gain(15_000L, enabled = true), 0.001f)
        assertEquals(0.25f, SleepFade.gain(7_500L, enabled = true), 0.001f)
        assertEquals(0f, SleepFade.gain(0L, enabled = true))
        assertEquals(0f, SleepFade.gain(-100L, enabled = true))
    }

    @Test
    fun theFadeStartsThirtySecondsBeforeTheDeadlineAndNotAtAllWhenOff() {
        assertEquals(70_000L, SleepFade.startsAt(100_000L, enabled = true))
        assertEquals(100_000L, SleepFade.startsAt(100_000L, enabled = false))
    }
}
