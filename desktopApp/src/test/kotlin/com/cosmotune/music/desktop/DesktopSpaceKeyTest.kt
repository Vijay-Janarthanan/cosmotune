package com.cosmotune.music.desktop

import com.cosmotune.design.AppMode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Space in the main window: the window hands its unclaimed keys to [DesktopSpaceKey], Music's play / pause is the one the
 * app registers, and a mode with nothing to play leaves the key alone. (How a focused text field or button keeps Space is
 * SpacePlayPauseInteractionTest's.)
 */
class DesktopSpaceKeyTest {
    @AfterTest fun forget() { DesktopSpaceKey.music = null }

    @Test fun musicTogglesTheSong() {
        var toggled = 0
        DesktopSpaceKey.music = { toggled++; true }
        assertTrue(DesktopSpaceKey.toggle(AppMode.MUSIC))
        assertEquals(1, toggled)
    }

    @Test fun musicWithNoSongLeavesTheKeyAlone() {
        DesktopSpaceKey.music = { false }
        assertFalse(DesktopSpaceKey.toggle(AppMode.MUSIC))
        DesktopSpaceKey.music = null
        assertFalse(DesktopSpaceKey.toggle(AppMode.MUSIC))
    }

    @Test fun aModeWithoutAPlayerLeavesTheKeyAlone() {
        DesktopSpaceKey.music = { error("Music is not the mode") }
        assertFalse(DesktopSpaceKey.toggle(AppMode.MANGA))
        assertFalse(DesktopSpaceKey.toggle(AppMode.READ))
    }

    @Test fun theWindowAndTheAppAreWired() {
        val main = source("Main.kt")
        assertTrue("onKeyEvent = { event -> DesktopSpaceKey.onKeyEvent(event, lookSettings.mode.value) }" in main,
            "the main window gives its unclaimed keys to the Space handler (onKeyEvent, after focus; not the preview)")
        val app = source("DesktopApp.kt")
        assertTrue("DesktopSpaceKey.music = { if (selectedSong != null) { togglePlayPauseFromUser(); true } else false }" in app,
            "Music's Space is the play button's toggle")
    }

    private fun source(name: String): File {
        val rel = "src/main/kotlin/com/cosmotune/music/desktop/$name"
        return listOf(File(rel), File("desktopApp/$rel")).first { it.isFile }
    }

    private operator fun File.contains(text: String): Boolean = readText().contains(text)
}
