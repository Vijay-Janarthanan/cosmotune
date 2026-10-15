package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab the phone shows when the saved tab is Explore (P4-20). The new look has three tabs and Explore lives inside Search, so Explore
 * without a mood page open is Search, and it is decided where the tab is read, before any frame composes Explore.
 */
class TabRedirectTest {
    private val explore = 1
    private val search = 3

    private fun shown(stored: Int, newLook: Boolean, moodPageOpen: Boolean) =
        TabRedirect.shown(stored, newLook = newLook, moodPageOpen = moodPageOpen, exploreTab = explore, searchTab = search)

    @Test fun exploreWithoutAMoodPageIsSearchInTheNewLook() {
        assertEquals(search, shown(explore, newLook = true, moodPageOpen = false))
    }

    @Test fun exploreWithAMoodPageStaysExplore() {
        assertEquals(explore, shown(explore, newLook = true, moodPageOpen = true))
    }

    @Test fun theOldLookKeepsItsExploreTab() {
        assertEquals(explore, shown(explore, newLook = false, moodPageOpen = false))
        assertEquals(explore, shown(explore, newLook = false, moodPageOpen = true))
    }

    @Test fun everyOtherTabIsWhatWasSaved() {
        for (tab in listOf(0, 2, search)) {
            for (newLook in listOf(true, false)) {
                for (mood in listOf(true, false)) assertEquals(tab, shown(tab, newLook, mood), "tab $tab, new look $newLook, mood page $mood")
            }
        }
    }
}
