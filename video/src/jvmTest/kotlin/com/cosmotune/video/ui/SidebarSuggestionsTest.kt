package com.cosmotune.video.ui

import com.cosmotune.video.ui.desktop.DesktopVideoState
import com.cosmotune.video.ui.desktop.openSidebarSuggestions
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.nav.VideoRoute
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Typing in the desktop sidebar's search field from any page shows the suggestions (Y-34, after Y-18's concern 1): the first
 * character typed on another page opens the search page, which keeps the typed text instead of taking its route's empty query.
 */
class SidebarSuggestionsTest {

    @AfterTest fun clear() {
        DesktopVideoState.keepTyped = false
    }

    @Test fun typingOnAnotherPageOpensTheSearchPageOnceAndItKeepsTheText() {
        val navigator = VideoNavigator()
        navigator.push(VideoRoute.History)
        openSidebarSuggestions(navigator, "")
        openSidebarSuggestions(navigator, "   ")
        assertEquals(VideoRoute.History, navigator.top, "nothing typed: the page stays")
        openSidebarSuggestions(navigator, "c")
        assertEquals(VideoRoute.Search(""), navigator.top, "the typing page, which shows the suggestions")
        assertTrue(DesktopVideoState.takeKeepTyped(), "the page keeps what is typed")
        assertFalse(DesktopVideoState.takeKeepTyped(), "once")
        openSidebarSuggestions(navigator, "ca")
        assertEquals(VideoRoute.Search(""), navigator.top)
        assertTrue(navigator.pop() && navigator.top == VideoRoute.History, "one search page was pushed, not one per character")
        assertFalse(DesktopVideoState.keepTyped)
    }

    @Test fun onAResultsPageThePageItselfFollowsTheText() {
        val navigator = VideoNavigator()
        navigator.push(VideoRoute.Search("cats"))
        openSidebarSuggestions(navigator, "dogs")
        assertEquals(VideoRoute.Search("cats"), navigator.top)
        assertFalse(DesktopVideoState.keepTyped)
    }

    @Test fun theFieldAndThePageAreWiredToIt() {
        val sidebar = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/desktop/VideoDesktopHeader.kt")
        assertTrue("onQueryChange = { DesktopVideoState.query = it; openSidebarSuggestions(navigator, it) }" in sidebar)
        val page = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/search/VideoSearch.kt")
        assertTrue("LaunchedEffect(route.query) { if (!DesktopVideoState.takeKeepTyped()) DesktopVideoState.query = route.query }" in page)
    }
}
