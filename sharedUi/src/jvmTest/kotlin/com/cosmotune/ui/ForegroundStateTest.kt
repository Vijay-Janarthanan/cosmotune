package com.cosmotune.ui

import androidx.lifecycle.Lifecycle
import com.cosmotune.music.ui.isOnScreen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundStateTest {
    @Test fun aDesktopWindowThatLostFocusIsStillOnScreen() {
        // Compose puts an unfocused desktop window in STARTED; the lyrics and the music video must keep moving there
        assertTrue(isOnScreen(Lifecycle.State.RESUMED, android = false))
        assertTrue(isOnScreen(Lifecycle.State.STARTED, android = false))
    }

    @Test fun aMinimisedDesktopWindowIsNot() {
        assertFalse(isOnScreen(Lifecycle.State.CREATED, android = false))
        assertFalse(isOnScreen(Lifecycle.State.DESTROYED, android = false))
    }

    @Test fun androidStillNeedsResumed() {
        assertTrue(isOnScreen(Lifecycle.State.RESUMED, android = true))
        assertFalse(isOnScreen(Lifecycle.State.STARTED, android = true), "behind a dialog or in split screen")
    }
}
