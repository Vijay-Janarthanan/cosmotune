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
        assertFalse(AppModes.available(AppMode.LIBRARY))
    }

    @Test
    fun thereAreThreeModesInTheOrderOfTheDrawer() {
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.MANGA, AppMode.LIBRARY), AppMode.entries.toList())
    }

    @Test
    fun everyModeIsAnsweredAndExactlyTwoAreAvailable() {
        assertEquals(3, AppMode.entries.count { AppModes.available(it) })
    }
}
