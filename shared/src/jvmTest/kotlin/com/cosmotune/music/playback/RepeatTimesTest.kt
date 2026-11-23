package com.cosmotune.music.playback

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeatTimesTest {
    @AfterTest fun reset() = RepeatTimes.clear()

    @Test fun threePlaysMeansTwoMoreThenTheQueueCarriesOn() {
        RepeatTimes.set("a", 3)
        assertEquals("2 more times", RepeatTimes.hold.value?.line)
        assertTrue(RepeatTimes.consume("a"))
        assertEquals("1 more time", RepeatTimes.hold.value?.line)
        assertTrue(RepeatTimes.consume("a"))
        assertNull(RepeatTimes.hold.value)
        assertFalse(RepeatTimes.consume("a"))
    }

    @Test fun anotherSongEndsTheHold() {
        RepeatTimes.set("a", 5)
        RepeatTimes.songChanged("a")
        assertEquals(4, RepeatTimes.hold.value?.left)
        RepeatTimes.songChanged("b")
        assertNull(RepeatTimes.hold.value)
        RepeatTimes.set("a", 2)
        assertFalse(RepeatTimes.consume("b"))
        assertNull(RepeatTimes.hold.value)
    }

    @Test fun oneOrNoPlaysIsNoHold() {
        RepeatTimes.set("a", 1)
        assertNull(RepeatTimes.hold.value)
        RepeatTimes.set("", 3)
        assertNull(RepeatTimes.hold.value)
    }
}
