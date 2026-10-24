package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mini player's window switch and published state; no window is opened here. */
class DesktopMiniPlayerTest {
    @Test fun toggleOpensAndClosesAndPublishedProgressIsClamped() {
        DesktopMiniPlayer.close()
        assertFalse(DesktopMiniPlayer.open.value)
        DesktopMiniPlayer.toggle(); assertTrue(DesktopMiniPlayer.open.value)
        DesktopMiniPlayer.toggle(); assertFalse(DesktopMiniPlayer.open.value)
        DesktopMiniPlayer.publish(DesktopMiniPlayer.NowPlaying("Kannalane", "A. R. Rahman", null, playing = true, progress = 1.7f))
        assertEquals(1f, DesktopMiniPlayer.state.value.progress)
        assertEquals("Kannalane", DesktopMiniPlayer.state.value.title)
        // Compact: a small fixed window, not a resizable second app.
        assertTrue(DesktopMiniPlayer.SIZE.width.value <= 400 && DesktopMiniPlayer.SIZE.height.value <= 140)
    }
}
