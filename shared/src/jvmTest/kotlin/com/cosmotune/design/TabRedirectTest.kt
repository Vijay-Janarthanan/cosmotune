package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab the phone shows for the saved tab. Numbers saved by older builds keep their meaning, Releases is appended, and nothing that
 * has no door is composed: it is decided where the tab is read, before any frame.
 */
class TabRedirectTest {
    private val home = 0
    private val explore = 1
    private val library = 2
    private val search = 3
    private val releases = 4

    private fun shown(stored: Int, newLook: Boolean) = TabRedirect.shown(stored, newLook = newLook, homeTab = home, releasesTab = releases)

    @Test fun exploreIsATabOfItsOwnInTheNewLook() {
        assertEquals(explore, shown(explore, newLook = true))
    }

    @Test fun theOldLookKeepsItsExploreTab() {
        assertEquals(explore, shown(explore, newLook = false))
    }

    @Test fun releasesIsShownInTheNewLookAndHasNoDoorInTheOld() {
        assertEquals(releases, shown(releases, newLook = true))
        assertEquals(home, shown(releases, newLook = false))
    }

    @Test fun aNumberSavedByAnOlderBuildKeepsItsPage() {
        for (newLook in listOf(true, false)) for (tab in listOf(home, explore, library, search)) assertEquals(tab, shown(tab, newLook))
    }

    @Test fun aSavedNumberThatIsNoTabIsHome() {
        for (newLook in listOf(true, false)) for (tab in listOf(-1, 5, 99)) assertEquals(home, shown(tab, newLook))
    }
}
