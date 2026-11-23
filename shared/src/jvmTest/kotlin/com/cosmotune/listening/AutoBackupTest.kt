package com.cosmotune.listening

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun offIsNeverDueAndAFirstBackupAlwaysIs() {
        assertFalse(AutoBackup.due(0, 0, 10 * day))
        assertTrue(AutoBackup.due(1, 0, 10 * day))
    }

    @Test fun dailyAndWeeklyWaitTheirTurn() {
        assertFalse(AutoBackup.due(1, 10 * day, 10 * day + day / 2))
        assertTrue(AutoBackup.due(1, 10 * day, 11 * day - 60_000))
        assertFalse(AutoBackup.due(7, 10 * day, 15 * day))
        assertTrue(AutoBackup.due(7, 10 * day, 17 * day))
    }

    @Test fun namesCarryTheDay() {
        val name = AutoBackup.fileName(0, ZoneOffset.UTC)
        assertEquals("cosmotune-auto-backup-1970-01-01.json", name)
        assertTrue(AutoBackup.isOurs(name))
        assertFalse(AutoBackup.isOurs("my-notes.json"))
    }

    @Test fun onlyTheNewestFiveOfOursStay() {
        val ours = (1..8).map { "cosmotune-auto-backup-2026-11-0$it.json" }
        val removed = AutoBackup.toRemove(ours.shuffled() + "cosmotune-backup-by-hand.json")
        assertEquals(ours.take(3).toSet(), removed.toSet())
    }
}
