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
        assertFalse(AppModes.available(AppMode.LIBRARY))
    }

    @Test
    fun thereAreThreeModesInTheOrderOfTheDrawer() {
        // Persisted by ordinal: READ is appended, nothing before it moves.
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.MANGA, AppMode.LIBRARY, AppMode.PODIO, AppMode.READ), AppMode.entries.toList())
    }

    @Test
    fun everyModeIsAnsweredAndExactlyTwoAreAvailable() {
        assertEquals(5, AppMode.entries.count { AppModes.available(it) })
    }

    @Test
    fun theSwitcherShowsTheAvailableModesInItsFixedOrder() {
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.PODIO, AppMode.READ, AppMode.MANGA, AppMode.LIBRARY), AppModes.switcherOrder)
        assertEquals(AppModes.switcherOrder.filter { AppModes.available(it) }, AppModes.shown())
        assertFalse(AppMode.LIBRARY in AppModes.shown(), "an unavailable mode is hidden, not greyed")
        assertEquals(AppMode.MUSIC, AppModes.forShortcut(1))
        assertEquals(AppMode.VIDEO, AppModes.forShortcut(2))
        assertEquals(null, AppModes.forShortcut(9))
    }
}
