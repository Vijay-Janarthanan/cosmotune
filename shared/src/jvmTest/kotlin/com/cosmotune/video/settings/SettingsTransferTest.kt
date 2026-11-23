package com.cosmotune.video.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTransferTest {
    @Test fun settingsTravelButThisDevicesOwnStay() {
        val there = VideoSettings(autoplay = false, hiddenWords = listOf("spoiler"), downloadFolder = "content://other", autoBackupLastMs = 5)
        val here = VideoSettings(downloadFolder = "content://mine", firstRunSeen = true, autoBackupLastMs = 9)
        val loaded = SettingsTransfer.read(SettingsTransfer.write(there))!!
        val applied = SettingsTransfer.apply(here, loaded)
        assertEquals(false, applied.autoplay)
        assertEquals(listOf("spoiler"), applied.hiddenWords)
        assertEquals("content://mine", applied.downloadFolder)
        assertEquals(true, applied.firstRunSeen)
        assertEquals(9L, applied.autoBackupLastMs)
    }

    @Test fun aLibraryExportIsNoSettingsFile() {
        assertNull(SettingsTransfer.read("""{"version":1,"subscriptions":[]}"""))
        assertNull(SettingsTransfer.read("not json"))
    }
}
