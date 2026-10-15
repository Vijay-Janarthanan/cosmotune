package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mode switch of the profile drawer: only Music works; Video and Library show "Later" (spec section 11, ruling O1). */
class AppModesTest {
    @Test
    fun onlyMusicIsAvailable() {
        assertTrue(AppModes.available(AppMode.MUSIC))
        assertFalse(AppModes.available(AppMode.VIDEO))
        assertFalse(AppModes.available(AppMode.LIBRARY))
    }

    @Test
    fun thereAreThreeModesInTheOrderOfTheDrawer() {
        assertEquals(listOf(AppMode.MUSIC, AppMode.VIDEO, AppMode.LIBRARY), AppMode.entries.toList())
    }

    @Test
    fun everyModeIsAnsweredAndExactlyOneIsAvailable() {
        assertEquals(1, AppMode.entries.count { AppModes.available(it) })
    }
}
