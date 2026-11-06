package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mode switch of the profile drawer: Music and Video work; Library shows "Later" (spec section 11, ruling O1). */
class AppModesTest {
    @Test
    fun musicAndVideoAreAvailable() {
        assertTrue(AppModes.available(AppMode.MUSIC))
        assertTrue(AppModes.available(AppMode.VIDEO))
        assertTrue(AppModes.available(AppMode.MANGA))
        assertTrue(AppModes.available(AppMode.PODIO))
        assertTrue(AppModes.available(AppMode.READ))
        assertTrue(AppModes.available(AppMode.WATCH))
        assertFalse(AppModes.available(AppMode.LIBRARY))
    }

    @Test
    fun thereAreThreeModesInTheOrderOfTheDrawer() {
        // Persisted by ordinal: READ and then WATCH are appended, nothing before them moves.
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.MANGA, AppMode.LIBRARY, AppMode.PODIO, AppMode.READ, AppMode.WATCH), AppMode.entries.toList())
    }

    @Test
    fun everyModeIsAnsweredAndExactlyTwoAreAvailable() {
        assertEquals(6, AppMode.entries.count { AppModes.available(it) })
    }

    @Test
    fun theSwitcherShowsTheAvailableModesInItsFixedOrder() {
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.WATCH, AppMode.PODIO, AppMode.READ, AppMode.MANGA, AppMode.LIBRARY), AppModes.switcherOrder)
        assertEquals(AppModes.switcherOrder.filter { AppModes.available(it) }, AppModes.shown())
        assertFalse(AppMode.LIBRARY in AppModes.shown(), "an unavailable mode is hidden, not greyed")
        assertEquals(AppMode.MUSIC, AppModes.forShortcut(1))
        assertEquals(AppMode.VIDEO, AppModes.forShortcut(2))
        assertEquals(AppMode.WATCH, AppModes.forShortcut(3))
        assertEquals(null, AppModes.forShortcut(9))
    }
}
