package com.cosmotune.music.ui.tv

import com.cosmotune.ui.watch.WatchTab
import org.junit.Assert.assertEquals
import org.junit.Test

class TvWatchTabsTest {
    @Test
    fun watchSearchComesFirstAfterTheWatchChipAndSaysWhatItFinds() {
        val tabs = tvWatchSubTabs()
        assertEquals(WatchTab.SEARCH, tabs.first())
        assertEquals(WatchTab.entries.size - 1, tabs.size)
        assertEquals("Search films & TV", tvWatchTabLabel(WatchTab.SEARCH))
        assertEquals("Films", tvWatchTabLabel(WatchTab.FILMS))
    }
}
