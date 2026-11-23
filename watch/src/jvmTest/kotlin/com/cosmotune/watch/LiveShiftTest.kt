package com.cosmotune.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveShiftTest {
    @Test fun saysHowFarBehindLiveTheChannelIs() {
        assertEquals("● Live", LiveShift.label(600_000, 590_000), "a few seconds behind is still live")
        assertEquals("Behind live by 2:10", LiveShift.label(600_000, 470_000))
        assertEquals("Behind live by 1:00:05", LiveShift.label(4_000_000, 4_000_000 - 3_605_000))
        assertEquals("● Live", LiveShift.label(20_000, 0), "a window too short to rewind in")
        assertFalse(LiveShift.canShift(0))
        assertTrue(LiveShift.isBehind(120_000, 60_000))
        assertEquals(0, LiveShift.behindMs(100_000, 120_000))
    }
}
