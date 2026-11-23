package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search sits in one place in every mode (the search-spotlight board, items 1, 2 and 3a): the round Search button beside the tabs
 * of the bottom chrome and at the head of the rail, never a magnifier in a top bar or beside a page title, and one search page for
 * Music, Video, Podio, Read and Manga. Read from the source (there is no Compose UI test harness for the shells here).
 */
class SearchEverywhereContractTest {
    private fun code(path: String) = SourceContract.code(SourceContract.read(path))
    private val podio = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/podio/ui"
    private val manga = "manga/src/jvmSharedMain/kotlin/com/cosmotune/manga/ui"
    private val read = "manga/src/jvmSharedMain/kotlin/com/cosmotune/reading/ui"

    @Test fun everyModesBottomChromeAndRailCarryTheRoundSearchButton() {
        val podioApp = code("$podio/PodioApp.kt")
        assertEquals(2, Regex("""search = searchNav,""").findAll(podioApp).count(), "Podio: the dock and the rail")
        assertTrue("CosmoNavSearch(\"Search\", selected = m.searching, onClick = ::tapSearch)" in podioApp)

        val frame = SourceContract.functionBody(SourceContract.read("$manga/ReadKit.kt"), "ReadModeFrame")
        assertEquals(2, Regex("""search = search,""").findAll(frame).count(), "Read and Manga: the dock and the rail")
        assertTrue("search = com.cosmotune.ui.shell.CosmoNavSearch(\"Search\", selected = navigation.searching, onClick = navigation::tapSearch)" in code("$manga/MangaApp.kt"))
        assertTrue("search = com.cosmotune.ui.shell.CosmoNavSearch(\"Search\", selected = navigation.search, onClick = navigation::tapSearch)" in code("$read/BooksApp.kt"))
        // Video's: VideoSearchButtonContractTest, in the Video module.
    }

    @Test fun noTopBarOrTitleRowCarriesAMagnifierAnyMore() {
        val kit = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/kit/KitHeaders.kt")
        assertTrue("fun KitRootActions(account: @Composable () -> Unit = {}, pageAction: @Composable () -> Unit = {})" in kit, "the root actions have no Search")
        assertFalse("KitIconButton(CosmoIcons.Search" in kit)
        for (file in listOf("$podio/PodioRadio.kt", "$podio/PodioPages.kt")) {
            assertFalse("OrbitPageButton(CosmoIcons.Search" in code(file), "$file: no magnifier in the page head")
        }
        for (file in listOf("$manga/MangaApp.kt", "$manga/MangaUpdates.kt", "$read/BooksApp.kt", "$read/BookDiscover.kt")) {
            val src = code(file)
            assertFalse("ReadIconButton(CosmoIcons.Search" in src, "$file: no magnifier beside the title")
            assertFalse("RootActions(" in src, "$file: the old root actions are gone")
        }
    }

    @Test fun whileSearchIsOpenNoTabIsLit() {
        val chrome = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoBottomChrome.kt")
        assertTrue("FloatingTabBar(tabs, if (search.selected) -1 else selectedIndex, onTabSelected, Modifier.weight(1f))" in chrome)
        assertTrue("index == selectedIndex && search?.selected != true" in code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoNavShell.kt"))
    }

    @Test fun pressingSearchAgainOnSearchClearsTheBoxAndFocusesIt() {
        assertTrue("fun tapSearch() { if (m.searching) { m.query = \"\"; m.browse(); searchFocus.request() } else openSearch() }" in code("$podio/PodioApp.kt"))
        assertTrue("if (searching && book == null) query = \"\" else" in code("$manga/MangaApp.kt"))
        assertTrue("if (search && book == null && publication == null) query = \"\" else" in code("$read/BooksApp.kt"))
    }

    @Test fun mangasBarHasFourPagesAndItsSourcesAreInTheProfileMenu() {
        assertTrue("val bar = listOf(LIBRARY, UPDATES, BROWSE, HISTORY)" in code("$manga/ReadLogic.kt"), "four pages beside Search at 360 dp")
        val app = code("$manga/MangaApp.kt")
        assertTrue("com.cosmotune.ui.chrome.LocalModeMenuEntries provides menu" in app)
        assertTrue("CosmoDrawerEntry(CosmoIcons.Explore, \"Manga sources\") { navigation.select(MangaTabs.SOURCES) }" in app, "what was under More")
        assertTrue("(modeEntries + entries)" in code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome/CosmoProfileDrawer.kt"), "the menu lists the mode's rows first")
    }

    @Test fun theDesktopSidebarsPutSearchFirstUnderTheModeChip() {
        val sidebar = code("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopSidebar.kt")
        val modeRows = sidebar.substringAfter("val searchOpen by searchNav.open.collectAsState()")
        assertTrue(modeRows.indexOf("OrbitNavModeChip()") < modeRows.indexOf("OrbitNavRow(CosmoIcons.Search, DesktopStrings[\"search\", \"Search\"], null, searchOpen) { searchNav.tap() }"))
        assertTrue(modeRows.indexOf("searchNav.tap()") < modeRows.indexOf("\"Radio\""), "Podio: before Radio")
        assertTrue(modeRows.indexOf("searchNav.tap()") < modeRows.indexOf("\"Library\""), "Read and Manga: before Library")
    }

    @Test fun everyModesSearchIsTheSharedPage() {
        val pages = mapOf(
            "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearch.kt" to "eyebrow = \"Music\"",
            "$podio/PodioPages.kt" to "eyebrow = \"Podio\"",
            "$read/BooksApp.kt" to "eyebrow = \"Read\"",
            "manga/src/jvmSharedMain/kotlin/com/cosmotune/manga/ui/MangaBrowse.kt" to "eyebrow = mangaText(\"Manga\")",
        )
        for ((file, eyebrow) in pages) {
            val src = code(file)
            assertTrue(Regex("""CosmoSearchPage\(""").containsMatchIn(src), "$file draws the shared search page")
            assertTrue(eyebrow in src, "$file: $eyebrow")
        }
    }

    @Test fun theSharedPageHasTheBoardsOrderAndStates() {
        val page = SourceContract.functionBody(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearchPage.kt"), "CosmoSearchPage")
        val order = listOf("SearchHead(", "OrbitSearchPill(", "\"search:kinds\"", "\"search:filters\"", "CountLine(", "results(")
        assertEquals(order, order.sortedBy { page.indexOf(it) }, "head, box, kinds, filters, count with Sort, results")
        for (state in listOf("CosmoSearchStatus.Idle ->", "CosmoSearchStatus.Typing ->", "CosmoSearchStatus.Searching,", "is CosmoSearchStatus.Empty ->", "is CosmoSearchStatus.Failed ->")) {
            assertTrue(state in page, "the page draws $state")
        }
        assertTrue("\"Clear filters\" to it" in page && "\"Try again\" to status.onRetry" in page)
    }

    @Test fun musicsLiveResultsQueueOnASwipeAndSearchCanStayInTheLibrary() {
        val search = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearch.kt")
        assertTrue("OrbitSelectChip(\"In my library\"" in search)
        assertTrue(SourceContract.functionBody(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearch.kt"), "TypeaheadSong")
            .contains("onSwipe = { actions.onSongSwipe(song) }"), "a live result queues on a swipe")
        val phone = code("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        assertTrue("onSongSwipe = onSearchSwipe," in phone && "searchUndo?.show(" in phone, "the phone's swipe offers Undo")
        assertTrue("myLibrary = com.cosmotune.ui.search.MyLibraryScope(searchInMyLibrary, { searchInMyLibrary = it }, searchMyLibrary)," in phone)
        val desktop = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue("searchUndo?.show(if (next) \"Plays next\" else \"Added to queue\")" in desktop, "the desktop's swipe offers Undo")
        assertTrue("myLibrary = com.cosmotune.ui.search.MyLibraryScope(searchInMyLibrary, { searchInMyLibrary = it }," in desktop)
    }
}
