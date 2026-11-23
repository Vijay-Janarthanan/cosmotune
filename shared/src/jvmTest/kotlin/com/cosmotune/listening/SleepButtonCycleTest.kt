package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepButtonCycleTest {
    @Test fun eachTapMovesToTheNextLengthThenOff() {
        assertEquals(15, SleepButtonCycle.next(null))
        assertEquals(30, SleepButtonCycle.next(15))
        assertEquals(30, SleepButtonCycle.next(14))
        assertEquals(60, SleepButtonCycle.next(30))
        assertNull(SleepButtonCycle.next(60))
        assertEquals(60, SleepButtonCycle.next(45), "a 45-minute timer from the player joins at 60")
        assertEquals("Sleep timer, 12 min left", SleepButtonCycle.label(12))
    }
}
