package com.cosmotune.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteButtonsTest {
    @Test fun fastForwardAndRewindSkipUntilChanged() {
        assertEquals(RemoteAction.SKIP_FORWARD, RemoteButtons.actionOf(emptyMap(), RemoteButton.FAST_FORWARD))
        assertEquals(RemoteAction.NONE, RemoteButtons.actionOf(emptyMap(), RemoteButton.RED))
        val red = RemoteButtons.with(emptyMap(), RemoteButton.RED, RemoteAction.NEXT_CHAPTER)
        assertEquals(RemoteAction.NEXT_CHAPTER, RemoteButtons.actionOf(red, RemoteButton.RED))
        assertEquals(emptyMap(), RemoteButtons.with(red, RemoteButton.RED, RemoteAction.NONE), "a default is not stored")
        assertEquals(RemoteAction.NONE, RemoteButtons.actionOf(mapOf("RED" to "GONE"), RemoteButton.RED), "an unknown name is nothing")
    }

    @Test fun anActionBecomesThePlayersKey() {
        assertEquals(PlayerKeyAction.SeekBy(-15_000), RemoteButtons.keyAction(RemoteAction.SKIP_BACK, 15))
        assertEquals(PlayerKeyAction.NextChapter, RemoteButtons.keyAction(RemoteAction.NEXT_CHAPTER, 10))
        assertNull(RemoteButtons.keyAction(RemoteAction.NONE, 10))
    }
}
