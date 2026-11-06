package com.cosmotune.ui.sleep

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepPausersTest {
    @Test
    fun everyAddedPauseRunsAndAFailingOneDoesNotStopTheRest() {
        var paused = 0
        val ok: () -> Unit = { paused++ }
        val broken: () -> Unit = { error("gone") }
        SleepPausers.add(broken)
        SleepPausers.add(ok)
        try {
            assertEquals(listOf("gone"), SleepPausers.pauseAll())
            assertEquals(1, paused)
            SleepPausers.remove(ok)
            SleepPausers.pauseAll()
            assertEquals(1, paused)
        } finally {
            SleepPausers.remove(ok)
            SleepPausers.remove(broken)
        }
    }
}
