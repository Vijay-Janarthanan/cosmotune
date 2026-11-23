package com.cosmotune.video.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun aBackupIsDueOnceItsTimeHasPassed() {
        assertFalse(AutoBackup.isDue(0, 0, 100 * day), "off")
        assertTrue(AutoBackup.isDue(1, 0, day))
        assertFalse(AutoBackup.isDue(7, 0, 3 * day))
        assertTrue(AutoBackup.isDue(7, 0, 7 * day))
    }

    @Test fun onlyTheNewestFiveBackupsAreKept() {
        val names = (1..7).map { AutoBackup.fileName("2026-11-0$it") } + "notes.txt"
        assertEquals(listOf(AutoBackup.fileName("2026-11-02"), AutoBackup.fileName("2026-11-01")), AutoBackup.toDelete(names))
    }
}
