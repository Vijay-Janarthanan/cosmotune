package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMixWatchdogTest {
    @Test fun `starvation is independent for each deck and resets on progress or pause`() {
        val guard = DesktopMixWatchdog(stallNanos = 10)
        assertFalse(guard.outgoingStalled(0))
        assertFalse(guard.incomingStalled(9))
        assertTrue(guard.outgoingStalled(10))
        assertFalse(guard.incomingStalled(10))
        guard.outgoingReady()
        assertTrue(guard.incomingStalled(19))
        assertFalse(guard.outgoingStalled(19))
        guard.resetClocks()
        assertFalse(guard.incomingStalled(99))
        assertFalse(guard.outgoingStalled(99))
    }

    @Test fun `fault budget drops beat matching then overlapping without changing playback settings`() {
        val guard = DesktopMixWatchdog()
        repeat(2) { guard.recordFault() }
        assertTrue(guard.beatMatchAllowed)
        guard.recordFault()
        assertFalse(guard.beatMatchAllowed)
        assertTrue(guard.overlapAllowed)
        repeat(3) { guard.recordFault() }
        assertFalse(guard.overlapAllowed)
        guard.resetClocks()
        assertFalse(guard.overlapAllowed)
    }
}
