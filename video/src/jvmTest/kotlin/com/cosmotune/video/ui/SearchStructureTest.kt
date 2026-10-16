package com.cosmotune.video.ui

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_error_bot
import com.cosmotune.video.resources.video_error_network
import com.cosmotune.video.resources.video_error_parsing
import com.cosmotune.video.resources.video_list_error
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.ui.search.SUGGESTION_DEBOUNCE_MS
import com.cosmotune.video.ui.search.SearchMode
import com.cosmotune.video.ui.search.SearchPhase
import com.cosmotune.video.ui.search.SearchSessions
import com.cosmotune.video.ui.search.SearchTab
import com.cosmotune.video.ui.search.SuggestionRow
import com.cosmotune.video.ui.search.SuggestionRows
import com.cosmotune.video.ui.search.mergeUnique
import com.cosmotune.video.ui.search.searchErrorMessage
import com.cosmotune.video.ui.search.searchFilters
import com.cosmotune.video.ui.search.searchKey
import com.cosmotune.video.ui.search.searchMode
import com.cosmotune.video.ui.search.visibleResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Search (plan task Y-18): the page of spec 5.3. What no render can show is read from the source (the branch, the debounce, where the
 * typed part is made bold, that every lazy item has a key, that no live colour is read outside a draw lambda), and the logic the
 * page is made of is run: which of the three pages the field's text asks for, the suggestion rows, the pages of results, and the small
 * cache that lets a Back from a video find the results where they were.
 */
class SearchStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/search"
    private val files = listOf("VideoSearch", "SearchSuggestions", "SearchResults")
    private fun read(name: String) = VideoSourceContract.read("$dir/$name.kt")
    private fun code(name: String) = VideoSourceContract.code(read(name))
    private val everything: String get() = files.joinToString("\n") { code(it) }

    /** What stands between the parentheses of the first call of [name] in [src] (comments already removed). */
    private fun callArguments(src: String, name: String): String {
        val open = src.indexOf("$name(") + name.length
        assertTrue(open >= name.length, "$name( is called")
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return src.substring(open + 1, i)
            }
        }
        error("the call of $name is never closed")
    }

    @AfterTest fun forgetTheSessions() = SearchSessions.clear()

    // ---- The files, the signature and the branch -------------------------------------------------------------------------

    @Test fun theDirectoryHoldsTheBriefsThreeFiles() {
        val names = java.io.File(VideoSourceContract.root, dir).listFiles { f -> f.extension == "kt" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(files.toSet(), names)
    }

    @Test fun theScreenKeepsTheSignatureOfTheBrief() {
        assertEquals(
            listOf("route: VideoRoute.Search", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(read("VideoSearch"), "VideoSearch"),
        )
        assertTrue("@Composable\nfun VideoSearch(" in read("VideoSearch"), "public, composable")
    }

    @Test fun theNavHostBranchIsOneLineWithAFullyQualifiedCall() {
        val host = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt")
        val lines = host.lines()
        val branch = lines.single { it.trim().startsWith("is VideoRoute.Search ->") }
        assertEquals("                is VideoRoute.Search -> com.cosmotune.video.ui.search.VideoSearch(r, contentPadding, wide)", branch)
        assertTrue(lines[lines.indexOf(branch) - 1].isBlank() && lines[lines.indexOf(branch) + 1].isBlank(), "the blank lines around the branch stay")
        assertFalse(lines.any { it.startsWith("import com.cosmotune.video.ui.search") }, "no new import line: the name is fully qualified")
        assertTrue("com.cosmotune.video.ui.actions.VideoActionsHost(contentPadding)" in host, "the action host stays")
    }

    @Test fun theSidebarsEnterRecordsTheSearchAndPushesTheSearchPage() {
        val sidebar = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/desktop/VideoDesktopSidebarContent.kt")
        val body = VideoSourceContract.functionBody(sidebar, "submitSidebarSearch").replace(Regex("\\s+"), " ")
        assertTrue("val q = query.trim()" in body)
        assertTrue("if (q.isNotEmpty()) DesktopVideoState.graph?.library?.addSearch(q)" in body, "the recent searches")
        assertTrue("navigator.push(VideoRoute.Search(q))" in body, "the search page")
        assertTrue("onSubmit = { submitSidebarSearch(navigator, DesktopVideoState.query) }" in sidebar, "the field's Enter calls it")
    }

    // ---- Spec 5.3: what the page is made of -----------------------------------------------------------------------------

    @Test fun theFieldIsPinnedAboveTheListAndFocusedWhenTheRouteOpensEmpty() {
        val page = code("VideoSearch")
        assertTrue("CosmoSearchField(" in page)
        assertFalse("height" in callArguments(page, "CosmoSearchField"), "the phone's field keeps its 52 dp")
        assertTrue("Res.string.video_search_hint" in page, "the hint")
        assertTrue("route.query.isEmpty()" in page && "focusRequester.requestFocus()" in page, "focused when the route opens empty")
        assertTrue("DesktopVideoState.query" in page, "the desktop's sidebar field is the field of the page")
        assertTrue("DesktopVideoState.navigator" in page && "ShellMetrics.DESKTOP_COMPACT_DP" in page, "a desktop window with no sidebar has the page's own field")
    }

    @Test fun aSubmitRecordsTheSearchAndReplacesThePage() {
        val page = code("VideoSearch")
        val submit = VideoSourceContract.functionBody(read("VideoSearch"), "submit")
        assertTrue("library.addSearch(" in submit && "navigator.replaceTop(VideoRoute.Search(" in submit)
        assertTrue("trim()" in submit, "the query is trimmed")
    }

    @Test fun theSuggestionsWaitTwoHundredFiftyMillisecondsAndMakeTheTypedPartBold() {
        assertEquals(250L, SUGGESTION_DEBOUNCE_MS)
        val src = code("SearchSuggestions")
        assertTrue("const val SUGGESTION_DEBOUNCE_MS = 250L" in src)
        assertTrue("delay(SUGGESTION_DEBOUNCE_MS)" in src && "source.suggestions(" in src)
        assertTrue("SearchHighlight.split(" in src, "the typed part is weight 700 through SearchHighlight")
        assertTrue("FontWeight.Bold" in src, "700")
        assertTrue("CosmoIcons.ArrowLeft" in src && "rotate = 45f" in src, "the fill arrow is the left arrow turned 45 degrees")
        assertTrue("ROW_MIN = 48.dp" in src, "48 dp rows")
        assertTrue("catch (e: CancellationException)" in src, "a cancelled lookup is not a failure")
    }

    @Test fun theRecentSearchesHaveARemoveButtonAndClearInTheOverline() {
        val src = code("SearchSuggestions")
        assertTrue("CosmoOverline(" in src && "Res.string.video_recent_searches" in src)
        assertTrue("Res.string.video_clear" in src && "CosmoButtonKind.TEXT" in src)
        assertTrue("Res.string.video_remove_search" in src && "CosmoIcons.Close" in src)
        assertTrue("Res.string.video_use_suggestion" in src, "the fill arrow has a description")
        val page = code("VideoSearch")
        assertTrue("library.removeSearch(" in page && "library.clearSearches()" in page)
    }

    @Test fun theResultsHaveTheFiveChipsAndTheShortsChipFollowsHideShorts() {
        val page = code("VideoSearch")
        assertTrue("VideoChips(" in page)
        for (key in listOf("video_filter_all", "video_filter_videos", "video_filter_shorts", "video_filter_channels", "video_filter_playlists")) {
            assertTrue("Res.string.$key" in page, key)
        }
        assertEquals(listOf(SearchFilter.ALL, SearchFilter.VIDEOS, SearchFilter.SHORTS, SearchFilter.CHANNELS, SearchFilter.PLAYLISTS), searchFilters(hideShorts = false))
        assertEquals(listOf(SearchFilter.ALL, SearchFilter.VIDEOS, SearchFilter.CHANNELS, SearchFilter.PLAYLISTS), searchFilters(hideShorts = true))
    }

    @Test fun theShortsFilterShowsTheShortsGridAndAShortOpensThePagerWithTheSearchsShorts() {
        val page = code("VideoSearch")
        assertTrue("ShortsGrid(" in page && "SearchFilter.SHORTS" in page)
        assertTrue("navigator.openShort(" in page && "ShortsOrigin.Search(" in page, "origin: this search")
        assertTrue("onLoadMore" in page, "the grid asks for the next page")
    }

    @Test fun theRowsAreTheBlocksOfThePageAndThePageHostsTheVideoSheet() {
        val results = code("SearchResults")
        assertTrue("VideoRow(" in results && "ChannelRow(" in results && "PlaylistRow(" in results)
        assertTrue("rowThumbnailSize(desktop = true)" in results && "VideoRowFrame(" in results, "a wide page's playlist row has the desktop thumbnail the block lacks")
        assertTrue("rememberVideoTitle(" in results && "rememberVideoThumbnail(" in results, "DeArrow only through Y-16's helpers")
        val page = code("VideoSearch")
        assertTrue("rememberVideoActions()" in page && "VideoActionsSheet(" in page && "rememberDownloadedIds()" in page)
        assertTrue("library.subscribe(" in everything && "library.unsubscribe(" in everything, "a channel row subscribes through the library")
        assertTrue("VideoRoute.Channel(" in everything && "VideoRoute.Playlist(" in everything, "a channel and a playlist open their pages")
        assertTrue("VideoSheetContext.DEFAULT" in page)
    }

    @Test fun anEmptyResultAndAFailureUseTheEmptyStateAndRetry() {
        val results = code("SearchResults")
        assertTrue("CosmoEmptyState(" in results)
        assertTrue("Res.string.video_no_results" in results && "Res.string.video_no_results_hint" in results)
        assertTrue("Res.string.video_retry" in results && "actionLabel" in results && "onAction" in results)
    }

    @Test fun theListIsAtMostEightHundredFortyWideAndCentredOnAWidePage() {
        val page = code("VideoSearch")
        assertTrue("widthIn(max = LIST_MAX_WIDTH)" in page && "LIST_MAX_WIDTH = 840.dp" in page)
        assertTrue("Alignment.CenterHorizontally" in page)
        assertTrue("desktop = wide" in code("SearchResults"), "the wide page's rows use the desktop thumbnail")
    }

    @Test fun moreResultsLoadNearTheEndOfTheList() {
        val results = code("SearchResults")
        assertTrue("snapshotFlow" in results && "LOAD_MORE_AHEAD" in results && "loadMore(" in results)
    }

    // ---- The rules every screen keeps ------------------------------------------------------------------------------------

    @Test fun everyLazyItemHasAStableKey() {
        for (name in files) {
            val src = code(name)
            val calls = Regex("""\b(items|item|itemsIndexed)\(""").findAll(src).toList()
            for (call in calls) {
                val rest = src.substring(call.range.last).take(180)
                assertTrue("key =" in rest, "$name: `${call.value}` has a key: ${rest.take(80)}")
            }
        }
    }

    @Test fun noLiveColourNoPositionAndNoColourPickedByHand() {
        val text = everything
        assertFalse(".live(" in text, "the search files draw nothing of their own: the blocks read the live palette")
        assertFalse("positionMs()" in text)
        assertFalse(Regex("""Color\(0x|Color\.(Black|White|Red|Gray|Transparent)|Color\(\d""").containsMatchIn(text), "colours come from the palette")
    }

    @Test fun theFilesNameNeitherTheSourceNorTheirLibrary() {
        for (name in files) {
            val src = read(name)
            assertFalse(Regex("(?i)\\b(youtube|yt)\\b").containsMatchIn(src), "$name names the source")
            assertFalse("org.schabi" in src, "$name reaches past the source")
            assertFalse("NewPipe" in src, "$name reaches past the source")
        }
    }

    @Test fun theTextsAreResourcesAndNothingIsAllocatedPerFrame() {
        val text = everything
        assertFalse(Regex("""Text\(\s*(text\s*=\s*)?"[^"]+"""").containsMatchIn(text), "no text literal")
        assertFalse("withFrameNanos" in text || "rememberInfiniteTransition" in text || "animate" in text, "nothing animates here")
    }

    // ---- Which page the field asks for -----------------------------------------------------------------------------------

    @Test fun anEmptyFieldIsTheIdlePageWhateverWasSearched() {
        assertEquals(SearchMode.IDLE, searchMode("", ""))
        assertEquals(SearchMode.IDLE, searchMode("   ", "cats"))
        assertEquals(SearchMode.IDLE, searchMode("", "cats"))
    }

    @Test fun textThatIsNotWhatWasSearchedIsTheTypingPage() {
        assertEquals(SearchMode.TYPING, searchMode("ca", ""))
        assertEquals(SearchMode.TYPING, searchMode("cats d", "cats"))
        assertEquals(SearchMode.TYPING, searchMode("cat", "cats"))
    }

    @Test fun theTextOfTheSearchIsTheResultsPageAndSpacesDoNotCount() {
        assertEquals(SearchMode.RESULTS, searchMode("cats", "cats"))
        assertEquals(SearchMode.RESULTS, searchMode("  cats ", "cats"))
        assertEquals(SearchMode.RESULTS, searchMode("cats", " cats  "))
    }

    // ---- The suggestion rows ----------------------------------------------------------------------------------------------

    @Test fun suggestionsComeFirstThenTheRecentSearchesThatMatch() {
        val rows = SuggestionRows.build(
            typed = "cat",
            suggestions = listOf("cat videos", "cats", "catapult"),
            recents = listOf("dogs", "black cat", "cat food"),
        )
        assertEquals(
            listOf(
                SuggestionRow("cat videos", false), SuggestionRow("cats", false), SuggestionRow("catapult", false),
                SuggestionRow("black cat", true), SuggestionRow("cat food", true),
            ),
            rows,
        )
    }

    @Test fun theTypedTextItselfAndRepeatsAreLeftOutAnyCase() {
        val rows = SuggestionRows.build(
            typed = "Cat",
            suggestions = listOf("cat", "CAT videos", "cat videos", "  ", "Cat", "cats "),
            recents = listOf("CAT VIDEOS", "cat", "cat toys"),
        )
        assertEquals(listOf("CAT videos", "cats", "cat toys"), rows.map { it.text })
        assertEquals(listOf(false, false, true), rows.map { it.recent })
    }

    @Test fun nothingTypedGivesNoRows() {
        assertTrue(SuggestionRows.build("", listOf("a"), listOf("b")).isEmpty())
        assertTrue(SuggestionRows.build("   ", listOf("a"), listOf("b")).isEmpty())
    }

    @Test fun theRowsAreCapped() {
        val many = (1..30).map { "cat $it" }
        val rows = SuggestionRows.build("cat", many, many.map { "my cat $it" })
        assertEquals(SuggestionRows.MAX_SUGGESTIONS + SuggestionRows.MAX_RECENT, rows.size)
        assertEquals(SuggestionRows.MAX_SUGGESTIONS, rows.count { !it.recent })
        assertEquals(many.take(SuggestionRows.MAX_SUGGESTIONS), rows.filter { !it.recent }.map { it.text }, "the source's order stays")
    }

    // ---- The pages of results ---------------------------------------------------------------------------------------------

    @Test fun theFirstPageLoadsAndTheTabIsReady() = runTest {
        val source = FakeVideoSource()
        val tab = SearchTab(source, "synth", SearchFilter.ALL, createdMs = 0)
        assertEquals(SearchPhase.NOT_STARTED, tab.phase)
        tab.start(backgroundScope)
        assertEquals(SearchPhase.LOADING, tab.phase)
        runCurrent()
        assertEquals(SearchPhase.READY, tab.phase)
        assertEquals(FakeVideoSource.PAGE_SIZE, tab.items.size)
        assertEquals(PageToken("search:ALL:10"), tab.next)
        assertEquals(listOf("search(synth,ALL)"), source.calls)
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(1, source.calls.size, "starting a tab that has loaded asks for nothing")
    }

    @Test fun moreResultsAppendUntilThereAreNoMoreAndNeverTwiceAtOnce() = runTest {
        val source = FakeVideoSource()
        val tab = SearchTab(source, "synth", SearchFilter.VIDEOS, createdMs = 0)
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(10, tab.items.size)
        tab.loadMore(backgroundScope)
        tab.loadMore(backgroundScope)
        assertTrue(tab.loadingMore)
        runCurrent()
        assertFalse(tab.loadingMore)
        assertEquals(12, tab.items.size, "the 12 videos of the fake")
        assertNull(tab.next)
        assertEquals(listOf("search(synth,VIDEOS)", "search(synth,VIDEOS)"), source.calls, "two calls in all: the second loadMore found one running")
        tab.loadMore(backgroundScope)
        runCurrent()
        assertEquals(2, source.calls.size, "at the end there is nothing to ask for")
    }

    @Test fun aRepeatedItemIsKeptOnce() {
        val a = ListItem.Video(FakeVideoSource.videos[0])
        val b = ListItem.Video(FakeVideoSource.videos[1])
        val c = ListItem.Channel(FakeVideoSource.channels[0])
        assertEquals(listOf(a, b), mergeUnique(listOf(a, b), listOf(b, a)))
        assertEquals(listOf(a, b, c), mergeUnique(listOf(a), listOf(b, b, c, a)))
        assertEquals(listOf(a, a).distinctBy { it.searchKey() }, mergeUnique(emptyList(), listOf(a, a)), "inside one page too")
        val same = listOf(a, b)
        assertSame(same, mergeUnique(same, listOf(a)), "nothing new: the list as it was")
    }

    @Test fun everyKindOfResultHasItsOwnKey() {
        val video = ListItem.Video(FakeVideoSource.videos[0])
        val channel = ListItem.Channel(ChannelItem("same", "n", null, null, null, null))
        val playlist = ListItem.Playlist(PlaylistItem("same", "t", null, null, null, null))
        val keys = listOf(video, channel, playlist).map { it.searchKey() }
        assertEquals(3, keys.toSet().size, "a channel and a playlist with the same id are two rows")
        assertEquals(video.searchKey(), ListItem.Video(FakeVideoSource.videos[0]).searchKey(), "stable")
    }

    @Test fun aFailedFirstPageShowsTheErrorAndTryAgainLoadsIt() = runTest {
        val source = FakeVideoSource()
        val tab = SearchTab(source, "synth", SearchFilter.ALL, createdMs = 0)
        source.failNext(VideoSourceError.Network("offline", null))
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(SearchPhase.FAILED, tab.phase)
        assertTrue(tab.error is VideoSourceError.Network)
        assertTrue(tab.items.isEmpty())
        tab.retry(backgroundScope)
        assertEquals(SearchPhase.LOADING, tab.phase)
        assertNull(tab.error)
        runCurrent()
        assertEquals(SearchPhase.READY, tab.phase)
        assertEquals(10, tab.items.size)
    }

    @Test fun aFailedTabIsLoadedAgainWhenThePageComesBack() = runTest {
        val source = FakeVideoSource()
        val tab = SearchTab(source, "synth", SearchFilter.ALL, createdMs = 0)
        source.failNext(VideoSourceError.Parsing("changed", null))
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(SearchPhase.FAILED, tab.phase)
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(SearchPhase.READY, tab.phase)
    }

    @Test fun aFailedNextPageKeepsTheRowsAndTryAgainAsksForItAgain() = runTest {
        val source = FakeVideoSource()
        val tab = SearchTab(source, "synth", SearchFilter.VIDEOS, createdMs = 0)
        tab.start(backgroundScope)
        runCurrent()
        source.failNext(VideoSourceError.Network("offline", null))
        tab.loadMore(backgroundScope)
        runCurrent()
        assertEquals(SearchPhase.READY, tab.phase, "the rows stay")
        assertEquals(10, tab.items.size)
        assertTrue(tab.moreFailed)
        assertFalse(tab.loadingMore)
        tab.retry(backgroundScope)
        runCurrent()
        assertFalse(tab.moreFailed)
        assertEquals(12, tab.items.size)
    }

    @Test fun aPageThatLeavesMidLoadLetsTheNextVisitStartAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(FakeVideoSource(), gate)
        val tab = SearchTab(source, "synth", SearchFilter.ALL, createdMs = 0)
        val page = CoroutineScope(coroutineContext + Job())
        tab.start(page)
        runCurrent()
        assertEquals(SearchPhase.LOADING, tab.phase)
        page.cancel()
        runCurrent()
        assertEquals(SearchPhase.NOT_STARTED, tab.phase, "a cancelled first page is not a loading tab for ever")
        gate.complete(Unit)
        tab.start(backgroundScope)
        runCurrent()
        assertEquals(SearchPhase.READY, tab.phase)
    }

    @Test fun aPageThatLeavesMidLoadMoreCanAskForTheNextPageAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(FakeVideoSource(), CompletableDeferred(Unit))
        val tab = SearchTab(source, "synth", SearchFilter.VIDEOS, createdMs = 0)
        tab.start(backgroundScope)
        runCurrent()
        source.gate = gate
        val page = CoroutineScope(coroutineContext + Job())
        tab.loadMore(page)
        runCurrent()
        assertTrue(tab.loadingMore)
        page.cancel()
        runCurrent()
        assertFalse(tab.loadingMore)
        assertFalse(tab.moreFailed, "leaving is not a failure")
        gate.complete(Unit)
        tab.loadMore(backgroundScope)
        runCurrent()
        assertEquals(12, tab.items.size)
    }

    @Test fun aTokenThatComesBackEndsTheList() = runTest {
        val looping = object : VideoSource by FakeVideoSource() {
            override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> =
                Page(listOf(ListItem.Video(FakeVideoSource.videos[if (page == null) 0 else 1])), PageToken("same"))
        }
        val tab = SearchTab(looping, "q", SearchFilter.ALL, createdMs = 0)
        tab.start(backgroundScope)
        runCurrent()
        tab.loadMore(backgroundScope)
        runCurrent()
        assertEquals(2, tab.items.size)
        assertNull(tab.next, "the same token again would ask for ever")
    }

    @Test fun hideShortsTakesTheShortsOutOfTheRowsAndKeepsChannelsAndPlaylists() {
        val items = listOf<ListItem>(
            ListItem.Channel(FakeVideoSource.channels[0]), ListItem.Video(FakeVideoSource.videos[0]), ListItem.Video(FakeVideoSource.shorts[0]),
            ListItem.Playlist(FakeVideoSource.playlist), ListItem.Video(FakeVideoSource.shorts[1]),
        )
        assertSame(items, visibleResults(items, hideShorts = false))
        val hidden = visibleResults(items, hideShorts = true)
        assertEquals(listOf("c:" + FakeVideoSource.CHANNEL_1, "v:fakevideo01", "p:" + FakeVideoSource.PLAYLIST_ID), hidden.map { it.searchKey() })
    }

    @Test fun theErrorMessageFollowsTheKindOfFailure() {
        assertSame(Res.string.video_error_network, searchErrorMessage(VideoSourceError.Network("x", null)))
        assertSame(Res.string.video_error_parsing, searchErrorMessage(VideoSourceError.Parsing("x", null)))
        assertSame(Res.string.video_error_bot, searchErrorMessage(VideoSourceError.BotCheck("x")))
        assertSame(Res.string.video_list_error, searchErrorMessage(VideoSourceError.NotAvailable("x")))
        assertSame(Res.string.video_list_error, searchErrorMessage(IllegalStateException("x")))
        assertSame(Res.string.video_list_error, searchErrorMessage(null))
    }

    // ---- The results a Back finds again -----------------------------------------------------------------------------------

    @Test fun theSameSearchFindsTheSameTabAndAnotherFilterOrQueryDoesNot() {
        val source = FakeVideoSource()
        val a = SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = 0)
        assertSame(a, SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = 1_000))
        assertSame(a, SearchSessions.tab(source, " synth ", SearchFilter.ALL, nowMs = 1_000), "spaces around the query do not make another search")
        assertNotSame(a, SearchSessions.tab(source, "synth", SearchFilter.VIDEOS, nowMs = 0))
        assertNotSame(a, SearchSessions.tab(source, "tape", SearchFilter.ALL, nowMs = 0))
        assertNotSame(a, SearchSessions.tab(FakeVideoSource(), "synth", SearchFilter.ALL, nowMs = 0), "another source (another graph) has its own")
    }

    @Test fun aTabOlderThanTenMinutesIsMadeAgain() {
        val source = FakeVideoSource()
        val a = SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = 0)
        assertSame(a, SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = SearchSessions.MAX_AGE_MS))
        val b = SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = SearchSessions.MAX_AGE_MS + 1)
        assertNotSame(a, b)
        assertEquals(SearchSessions.MAX_AGE_MS + 1, b.createdMs)
        assertSame(b, SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = SearchSessions.MAX_AGE_MS + 2))
    }

    @Test fun theCacheKeepsTheLatestTabsOnly() {
        val source = FakeVideoSource()
        val first = SearchSessions.tab(source, "q0", SearchFilter.ALL, nowMs = 0)
        for (n in 1..SearchSessions.MAX_TABS) SearchSessions.tab(source, "q$n", SearchFilter.ALL, nowMs = 0)
        assertNotSame(first, SearchSessions.tab(source, "q0", SearchFilter.ALL, nowMs = 0), "the oldest one was let go")
        assertTrue(SearchSessions.size <= SearchSessions.MAX_TABS)
    }

    @Test fun aSessionCannotBeTheSameQueryInAnotherCaseByAccident() {
        val source = FakeVideoSource()
        val lower = SearchSessions.tab(source, "synth", SearchFilter.ALL, nowMs = 0)
        assertNotEquals(lower, SearchSessions.tab(source, "SYNTH", SearchFilter.ALL, nowMs = 0), "the source may answer another case differently")
    }

    /** A source whose search waits for [gate]. */
    private class GatedSource(private val inner: FakeVideoSource, var gate: CompletableDeferred<Unit>) : VideoSource by inner {
        override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> {
            gate.await()
            return inner.search(query, filter, page)
        }
    }
}
