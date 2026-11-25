package com.cosmotune.music.ui.tv

import com.cosmotune.ui.watch.WatchTab
import org.junit.Assert.assertEquals
import org.junit.Test

class TvWatchTabsTest {
    @Test
    fun watchSearchComesFirstAfterTheWatchChipAndSaysWhatItFinds() {
        val tabs = tvWatchSubTabs()
        assertEquals(WatchTab.SEARCH, tabs.first())
        // Home is the Watch chip itself, and Watch's settings live in the common Settings screen.
        assertEquals(WatchTab.entries.filter { it != WatchTab.HOME && it != WatchTab.SETTINGS }, tabs.sorted())
        assertEquals("Search films & TV", tvWatchTabLabel(WatchTab.SEARCH))
        assertEquals("Films", tvWatchTabLabel(WatchTab.FILMS))
    }
}
