package com.cosmotune.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckScheduleTest {
    private val now = 100_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test fun launchAlwaysChecks() = assertTrue(UpdateCheckSchedule.shouldCheck(now, now - minute, UpdateTrigger.LAUNCH))

    @Test fun returnWithinAnHourDoesNot() = assertFalse(UpdateCheckSchedule.shouldCheck(now, now - 59 * minute, UpdateTrigger.RETURNED))

    @Test fun returnAfterAnHourDoes() = assertTrue(UpdateCheckSchedule.shouldCheck(now, now - 61 * minute, UpdateTrigger.RETURNED))

    @Test fun periodicWaitsSixHours() {
        assertFalse(UpdateCheckSchedule.shouldCheck(now, now - 6 * hour + minute, UpdateTrigger.PERIODIC))
        assertTrue(UpdateCheckSchedule.shouldCheck(now, now - 6 * hour, UpdateTrigger.PERIODIC))
    }

    @Test fun neverCheckedChecks() = UpdateTrigger.entries.forEach { assertTrue(UpdateCheckSchedule.shouldCheck(now, null, it)) }

    @Test fun failedCheckStillAllowsNextAfterGap() {
        // A check that failed (offline, rate limited) still counts as an attempt: no retry storm, but the next one comes.
        val failedAt = now
        assertFalse(UpdateCheckSchedule.shouldCheck(failedAt + 30 * minute, failedAt, UpdateTrigger.RETURNED))
        assertTrue(UpdateCheckSchedule.shouldCheck(failedAt + 61 * minute, failedAt, UpdateTrigger.RETURNED))
    }
}
