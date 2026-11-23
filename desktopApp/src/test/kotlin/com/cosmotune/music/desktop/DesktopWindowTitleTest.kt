package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowTitleTest {
    @Test fun aPlayingSongNamesTheWindow() {
        assertEquals("Song – Artist · Cosmotune", DesktopWindowTitle.of("Song", "Artist", playing = true))
        assertEquals("Song · Cosmotune", DesktopWindowTitle.of("Song", " ", playing = true))
    }

    @Test fun pausedOrEmptyIsJustTheApp() {
        assertEquals("Cosmotune", DesktopWindowTitle.of("Song", "Artist", playing = false))
        assertEquals("Cosmotune", DesktopWindowTitle.of(null, null, playing = true))
    }

    @Test fun aLongTitleIsCut() {
        val title = DesktopWindowTitle.of("x".repeat(200), "Artist", playing = true)
        assertTrue(title.length < 110 && title.endsWith("… · Cosmotune"))
    }
}
