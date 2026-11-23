package com.cosmotune.video.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Video's part of "Search sits in one place in every mode" (the search-spotlight board, items 1 and 2): the round Search button in
 * the dock and at the head of the rail, the first row of the desktop sidebar, never a magnifier in the top bar, and the shared
 * search page.
 */
class VideoSearchButtonContractTest {
    private val ui = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui"
    private fun code(path: String) = VideoSourceContract.code(VideoSourceContract.read("$ui/$path"))

    @Test fun theDockAndTheRailCarryTheRoundSearchButton() {
        val tabs = code("shell/VideoTabs.kt")
        assertTrue("search = videoNavSearch(navigator)," in tabs, "the dock")
        assertTrue("selected = navigator.searching, onClick = navigator::tapSearch" in tabs, "lit on the search page, when no tab is")
        assertTrue("search = com.cosmotune.video.ui.shell.videoNavSearch(navigator)," in code("CosmoVideoApp.kt"), "the phone's rail")
        assertTrue("search = com.cosmotune.video.ui.shell.videoNavSearch(navigator)," in code("desktop/VideoDesktopEntry.kt"), "a narrow window's rail")
    }

    @Test fun theTopBarHasNoMagnifier() {
        assertFalse("onSearch" in code("CosmoVideoApp.kt"))
        assertFalse("CosmoIcons.Search" in code("CosmoVideoApp.kt"))
    }

    @Test fun pressingSearchOnSearchEmptiesTheBoxAndFocusesIt() {
        val navigator = code("shell/VideoNavigator.kt")
        assertTrue("searchClears++" in navigator && "searchFocus.request()" in navigator)
        assertTrue("if (clears != seenClears) { if (sidebar) DesktopVideoState.query = \"\" else own = \"\" }" in code("search/VideoSearch.kt"))
    }

    @Test fun theDesktopSidebarsFirstRowIsSearch() {
        val sidebar = code("desktop/VideoDesktopSidebarContent.kt")
        assertTrue(sidebar.indexOf("item(key = \"nav:search\")") in 0 until sidebar.indexOf("items(nav.take(PRIMARY_ROWS)"))
        assertTrue(sidebar.indexOf("RailItem(CosmoIcons.Search") in 0 until sidebar.indexOf("for (item in nav.take(PRIMARY_ROWS))"), "and of the folded rail")
    }

    @Test fun theSearchPageIsTheSharedOneWithTheSourcesOwnKindsOnly() {
        val page = code("search/VideoSearch.kt")
        assertTrue("CosmoSearchPage(" in page && "eyebrow = \"Video\"" in page)
        assertFalse(Regex("""(?m)^\s+filters = """).containsMatchIn(page), "the video source offers no upload-date, length or sort filter, so none is shown")
    }
}
