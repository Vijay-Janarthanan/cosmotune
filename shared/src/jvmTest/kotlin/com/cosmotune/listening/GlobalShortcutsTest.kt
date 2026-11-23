package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalShortcutsTest {
    @Test fun everyActionHasOneCtrlAltKeyAndTheVolumeKeysStayInRange() {
        assertEquals(GlobalShortcuts.Action.entries.toSet(), GlobalShortcuts.BINDINGS.map { it.action }.toSet())
        assertEquals(GlobalShortcuts.BINDINGS.size, GlobalShortcuts.BINDINGS.map { it.windowsKey }.toSet().size)
        assertEquals("Ctrl+Alt+Space", GlobalShortcuts.BINDINGS.first().label)
        assertEquals(0.55f, GlobalShortcuts.stepVolume(0.5f, GlobalShortcuts.Action.VOLUME_UP))
        assertEquals(1f, GlobalShortcuts.stepVolume(0.98f, GlobalShortcuts.Action.VOLUME_UP))
        assertEquals(0f, GlobalShortcuts.stepVolume(0.02f, GlobalShortcuts.Action.VOLUME_DOWN))
        assertEquals(0.5f, GlobalShortcuts.stepVolume(0.5f, GlobalShortcuts.Action.NEXT))
        assertEquals(true, GlobalShortcuts.summary().startsWith("Ctrl+Alt+Space play or pause · Ctrl+Alt+Right next song"))
    }
}
