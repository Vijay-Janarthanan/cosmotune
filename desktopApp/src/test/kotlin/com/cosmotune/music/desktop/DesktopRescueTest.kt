package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRescueTest {
    @Test fun rescueOpensWhenAskedOrAfterTwoStartsThatNeverGotGoing() {
        assertFalse(DesktopRescue.due(emptyArray(), 0))
        assertFalse(DesktopRescue.due(emptyArray(), 1))
        assertTrue(DesktopRescue.due(emptyArray(), 2))
        assertTrue(DesktopRescue.due(arrayOf("--rescue"), 0))
    }
}
