package com.cosmotune.ui

import com.cosmotune.ui.search.SearchSectionKind
import com.cosmotune.ui.search.searchTop
import com.cosmotune.ui.search.tileColumns
import com.cosmotune.ui.search.searchSections
import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to be
 * added): the seam of the three entry switches (Search, Explore, a mood's page: upstream's parameter lists, each body the one new
 * call), the six call sites in the roots, the old Search screen's behaviour that the new one copied (the old screen itself is
 * gone), and the rules the new pages were written under.
 */
class SearchContractTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search"
    private fun search(name: String): String = SourceContract.read("$dir/$name.kt")
    private val old = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/screens"
    private val oldExplore get() = SourceContract.read("$old/ExploreScreen.kt")
    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"

    private fun params(src: String, name: String) = SourceContract.parameters(src, name)
    private fun names(src: String, name: String) = SourceContract.names(params(src, name))

    /** The body of a switch, white space collapsed. */
    private fun flatBody(src: String, switch: String): String = SourceContract.functionBody(src, switch).replace(Regex("\\s+"), " ")

    // ---- the seams: the parameter lists ----

    @Test fun searchSwitchKeepsSearchScreensListAndBrowseLast() {
        val switch = params(search("SearchSwitch"), "SearchSwitch")
        assertEquals(46, switch.size, "upstream's SearchScreen list (37, the seven of the Add-ons source, BC-31, and Search in), then browse: $switch")
        assertEquals("browse: CosmoBrowse? = null", switch.last())
        assertEquals(listOf("query", "onQueryChange", "filter"), SourceContract.names(switch).take(3), "in upstream's order")
    }

    @Test fun theOldScreensDefaultForTheLibraryTapSurvives() {
        val switch = params(search("SearchSwitch"), "SearchSwitch")
        assertTrue("onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick" in switch, "the Library tap still defaults to the song tap")
    }

    @Test fun cosmoSearchTakesTheSwitchesList() =
        assertEquals(params(search("SearchSwitch"), "SearchSwitch"), params(search("CosmoSearch"), "CosmoSearch"))

    @Test fun exploreSwitchTakesExploreScreensList() {
        val oldList = params(oldExplore, "ExploreScreen")
        assertEquals(10, oldList.size)
        assertEquals(oldList, params(search("ExploreSwitch"), "ExploreSwitch"))
        assertEquals(oldList, params(search("CosmoBrowsePage"), "CosmoBrowsePage"))
    }

    @Test fun moodGenreSwitchTakesMoodGenrePlaylistsScreensList() {
        val oldList = params(oldExplore, "MoodGenrePlaylistsScreen")
        assertEquals(7, oldList.size)
        assertEquals(oldList, params(search("MoodGenreSwitch"), "MoodGenreSwitch"))
        assertEquals(oldList, params(search("CosmoMoodPage"), "CosmoMoodPage"))
    }

    // ---- the seams: the one call ----

    @Test fun eachSwitchIsTheOneNewCallAndPassesEveryParameterByName() {
        data class Case(val file: String, val switch: String, val new: String, val oldCall: String)
        val cases = listOf(
            Case("SearchSwitch", "SearchSwitch", "CosmoSearch(", "SearchScreen("),
            Case("ExploreSwitch", "ExploreSwitch", "CosmoBrowsePage(", "ExploreScreen("),
            Case("MoodGenreSwitch", "MoodGenreSwitch", "CosmoMoodPage(", "MoodGenrePlaylistsScreen("),
        )
        for (case in cases) {
            val src = search(case.file)
            val body = flatBody(src, case.switch)
            assertTrue(body.startsWith("{ ${case.new}") && body.endsWith(") }"), "${case.switch}: the body is the one call of ${case.new} $body")
            assertEquals(1, Regex(Regex.escape(case.new)).findAll(body).count(), "${case.switch}: one call")
            for (name in names(src, case.switch)) {
                assertTrue(Regex("""\b$name = $name\b""").containsMatchIn(body), "${case.switch}: passes $name = $name")
            }
        }
    }

    // ---- the six call sites ----

    @Test fun bothRootsCallTheThreeSwitchesAndNotTheOldScreens() {
        for (path in listOf(mainActivity, desktopApp)) {
            val src = SourceContract.code(SourceContract.read(path))
            for (switch in listOf("MoodGenreSwitch", "ExploreSwitch", "SearchSwitch")) {
                assertEquals(1, Regex("""com\.cosmotune\.ui\.search\.$switch\(""").findAll(src).count(), "$path calls $switch once, fully qualified")
            }
            for (old in listOf("MoodGenrePlaylistsScreen", "ExploreScreen", "SearchScreen")) {
                assertFalse(Regex("""\b$old\(""").containsMatchIn(src), "$path no longer calls $old directly")
            }
        }
    }

    @Test fun theSearchCallPassesExploresMoodsAndGenresAndTheWayToOpenOne() {
        val phone = SourceContract.code(SourceContract.read(mainActivity)).substringAfter("com.cosmotune.ui.search.SearchSwitch(")
        assertTrue(
            "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { viewModel.openMoodGenre(it); selectedTab = TAB_EXPLORE }, onRetry = viewModel::loadExplore)," in phone,
            "the phone hands over the Explore state; a mood opens its page under the Explore tab (the page lives there)",
        )
        // inside the call: before the Library flag and not after the call has closed
        assertTrue(phone.indexOf("browse = com.cosmotune.ui.browse.CosmoBrowse(") < phone.indexOf("searchingLibrary = searchSource == SearchSource.LIBRARY,"))
        val desktop = SourceContract.code(SourceContract.read(desktopApp)).substringAfter("com.cosmotune.ui.search.SearchSwitch(")
        assertTrue(
            "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { selectDestination(DesktopDestination.EXPLORE); openMoodGenre(it) }, onRetry = { exploreReloads++ })," in desktop,
            "the desktop hands over the Explore state; selecting the destination clears the mood, so it comes first",
        )
        assertTrue(desktop.indexOf("showField = true,") in 0 until desktop.indexOf("browse = com.cosmotune.ui.browse.CosmoBrowse("), "the desktop draws the page's search pill")
        assertTrue("showField = true," in phone, "the phone draws the page's search pill too")
        assertTrue(desktop.indexOf("browse = com.cosmotune.ui.browse.CosmoBrowse(") < desktop.indexOf("destination == DesktopDestination.LIBRARY"), "the argument is inside the Search call")
    }

    // ---- what the new page copies from the old one ----

    @Test fun theThreeEffectsAndTheTwoFlagsAreTheOldScreensWordForWord() {
        // copied from the old screen while it was there, word for word; the old screen is gone, the copy stays
        val new = SourceContract.code(search("CosmoSearch"))
        val lines = listOf(
            "LaunchedEffect(focusRequested) {",
            "if (focusRequested && showField) {",
            "focusRequester.requestFocus()",
            "keyboardController?.show()",
            "onFocusHandled()",
            "LaunchedEffect(scrollResetTrigger) {",
            "if (scrollResetTrigger > 0) listState.scrollToItem(0)",
            "val suggesting = suggestions.isNotEmpty() && !searchingLibrary && !searchingAddons",
            "val showTypeahead = typeaheadResults.isNotEmpty() && suggesting",
            "LaunchedEffect(listState, results, loadingMore, searchingLibrary, searchingAddons) {",
            "if (results !is UiState.Success || searchingLibrary || searchingAddons) return@LaunchedEffect",
            "(layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount",
            "if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()",
        )
        for (line in lines) {
            assertTrue(line in new, "CosmoSearch has, as the old screen had: $line")
        }
        assertTrue("com.cosmotune.music.ui.screens.SearchScreen" in search("CosmoSearch"), "a comment names the source of the copy")
    }

    @Test fun theFilterChipsAreTheOldFiltersAndComeUnderTheOldConditions() {
        val new = SourceContract.code(search("CosmoSearch"))
        assertTrue("if (results != null && !suggesting && !searchingLibrary && !searchingAddons) {" in new)
        assertTrue("SearchFilter.entries.sortedBy { FILTER_ORDER.indexOf(it)" in new, "every filter the search offers, in the board's order")
        // P4-19: the chip that is already chosen does not buzz again
        assertTrue("OrbitSelectChip(entry.label, selected = entry == filter, fonts = fonts, look = look, onClick = { onFilterChange(entry) }, haptic = entry != filter)" in new)
        assertTrue("visible = query.isEmpty()," in new, "the Library switcher is up only while nothing is typed, as the old")
        assertTrue("contentPadding.calculateBottomPadding()" in new, "the list takes the bottom of contentPadding")
        assertTrue("if (showField) {" in new && "OrbitSearchPill(" in new, "the big search pill heads the page")
    }

    @Test fun theSuggestionsAreTheOldOnesAndTheTypedPartIsBold() {
        val new = SourceContract.code(search("CosmoSearch"))
        assertTrue("suggestions.drop(1).take(3)" in new, "the echo of the typed text is skipped and three follow, as the old")
        assertTrue("SearchHighlight.split(term, typed)" in new)
        assertTrue("FontWeight.Bold" in new)
        assertTrue("onSuggestionClick(term)" in new && "focusManager.clearFocus()" in new, "a tap searches it and puts the keyboard away")
        assertTrue("rotate = 45f" in new && "CosmoIcons.ArrowLeft" in new, "the fill arrow is the left arrow turned 45 degrees")
        assertTrue("Res.string.cosmo_search_use" in new, "and says what it does")
        assertTrue("heightIn(min = 48.dp)" in new)
    }

    @Test fun theIdlePageIsRecentsThenTheTilesAndFallsBackToTheOldSentence() {
        val new = SourceContract.code(search("CosmoSearch"))
        val idle = new.substringAfter("private fun LazyListScope.idleRows(").substringBefore("private fun LazyListScope.recentHeader(")
        assertTrue(idle.indexOf("recentChips(") in 0 until idle.indexOf("browseTiles("), "the recent searches come before the moods and genres (the board)")
        assertTrue("Res.string.search_empty" in idle, "nothing to show: the old sentence")
        assertTrue("together = true" in idle, "one section, Moods and genres")
        val blocks = SourceContract.code(search("CosmoBrowseBlocks"))
        assertTrue("OrbitMoodTile(" in blocks && "mood.stripeColor" in blocks, "the tiles are Orbit's, in the stripe colour")
        assertTrue("BrowseSplit.moods(" in blocks && "BrowseSplit.genres(" in blocks)
        assertTrue("onHistoryClear" in new && "Res.string.recent_search_remove" in new, "the recents can be removed one by one and cleared")
    }

    // ---- the pure part: which sections a result page has ----

    private fun track(id: String) = SearchResult.Track(Song(videoId = id, title = id, artist = "a", thumbnailUrl = null))
    private fun browse(id: String, type: BrowseType) = SearchResult.Browse(BrowseItem(id, id, "", null, type))

    @Test fun allSplitsTheResultsIntoSongsArtistsAlbumsPlaylistsAndTheRestAndDropsEmptyOnes() {
        val rows = listOf(
            SearchResult.TopTrack(Song(videoId = "top", title = "top", artist = "a", thumbnailUrl = null)),
            track("t1"), browse("ar1", BrowseType.ARTIST), browse("al1", BrowseType.ALBUM), track("t2"),
            browse("pl1", BrowseType.PLAYLIST), browse("x", BrowseType.OTHER),
        )
        val sections = searchSections(rows, SearchFilter.ALL)
        assertEquals(
            listOf(SearchSectionKind.SONGS, SearchSectionKind.ARTISTS, SearchSectionKind.ALBUMS, SearchSectionKind.PLAYLISTS, SearchSectionKind.MORE),
            sections.map { it.kind },
        )
        assertEquals(2, sections.first().rows.size, "the promoted top track has its own tile: it is not among the songs")
        assertEquals(listOf(SearchSectionKind.ARTISTS), searchSections(listOf(browse("ar1", BrowseType.ARTIST)), SearchFilter.ALL).map { it.kind })
        assertTrue(searchSections(emptyList(), SearchFilter.ALL).isEmpty())
    }

    @Test fun aFilterShowsItsRowsAsOneSectionWithoutAHeading() {
        val rows = listOf(track("t1"), track("t2"), browse("ar1", BrowseType.ARTIST))
        for (filter in SearchFilter.entries.filter { it != SearchFilter.ALL }) {
            val sections = searchSections(rows, filter)
            assertEquals(1, sections.size)
            assertEquals(SearchSectionKind.PLAIN, sections.single().kind)
            assertEquals(null, sections.single().kind.title, "no heading")
            assertEquals(rows, sections.single().rows)
        }
    }

    @Test fun everySectionKindButThePlainOneHasAHeadingFromTheStrings() {
        for (kind in SearchSectionKind.entries) assertEquals(kind != SearchSectionKind.PLAIN, kind.title != null, "$kind")
    }

    // ---- the tiles ----

    @Test fun thePhoneHasTwoTilesToARowAndAWidePageAsManyAsFitUpToSix() {
        assertEquals(2, tileColumns(360f, 16f))
        assertEquals(2, tileColumns(599f, 16f))
        assertEquals(3, tileColumns(700f, 28f), "never fewer than three once wide")
        assertEquals(4, tileColumns(960f, 28f))
        assertEquals(6, tileColumns(3000f, 28f))
    }

    // ---- the top result ----

    @Test fun theTopResultIsThePromotedCardOfAllAndLeavesItsSection() {
        val artist = browse("ar1", BrowseType.ARTIST)
        val rows = listOf(artist, track("t1"), browse("ar2", BrowseType.ARTIST))
        assertEquals(artist, searchTop(rows, SearchFilter.ALL), "an artist card at the head is the top result")
        assertEquals(null, searchTop(rows, SearchFilter.ARTISTS), "a filtered page has none")
        assertEquals(listOf("ar2"), searchSections(rows, SearchFilter.ALL, artist).first { it.kind == SearchSectionKind.ARTISTS }.rows.map { (it as SearchResult.Browse).item.browseId })
        val top = SearchResult.TopTrack(Song(videoId = "top", title = "top", artist = "a", thumbnailUrl = null))
        assertEquals(top, searchTop(listOf(top, track("t1")), SearchFilter.ALL))
        assertEquals(null, searchTop(listOf(track("t1")), SearchFilter.ALL), "a page that leads with a plain song has no card")
    }

    // ---- the rules the new pages were written under ----

    private val searchFiles = listOf("SearchSwitch", "ExploreSwitch", "MoodGenreSwitch", "CosmoSearch", "CosmoSearchResults", "CosmoBrowseBlocks", "CosmoBrowsePage", "CosmoMoodPage")

    /** The text before the `{` of every block that encloses [index], innermost first (braces in string templates balance). */
    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer)\s*(\([^)]*\))?\s*$""")

    @Test fun noLivePaletteColourIsReadOutsideADrawPhaseLambda() {
        for (name in searchFiles) {
            val code = SourceContract.code(search(name))
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                assertTrue(enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }, "$name: live( at line ${code.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
    }

    @Test fun noColourIsPickedByHandAndNoBrushIsRemembered() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient)""")
        for (name in searchFiles) {
            val code = SourceContract.code(search(name))
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(code), "$name uses a named colour")
            assertFalse(remembering.containsMatchIn(code), "$name remembers a brush or a live colour")
        }
    }

    @Test fun theListsAreLazyWithKeysAndNothingIsBuiltFromRawM3Colours() {
        for (name in listOf("CosmoSearch", "CosmoSearchResults", "CosmoBrowseBlocks", "CosmoBrowsePage", "CosmoMoodPage")) {
            val code = SourceContract.code(search(name))
            assertFalse(Regex("""MaterialTheme\.colorScheme""").containsMatchIn(code), "$name takes its colours from the palette, not from Material")
        }
        for (name in listOf("CosmoSearch", "CosmoBrowsePage", "CosmoMoodPage")) {
            assertTrue("LazyColumn(" in SourceContract.code(search(name)), "$name is a lazy list")
        }
        // every `items(` and `itemsIndexed(` of the pages names a key
        for (name in listOf("CosmoSearch", "CosmoSearchResults", "CosmoBrowseBlocks", "CosmoMoodPage")) {
            val code = SourceContract.code(search(name))
            for (hit in Regex("""\bitems(Indexed)?\(""").findAll(code)) {
                val call = code.substring(hit.range.first, minOf(code.length, hit.range.first + 220))
                assertTrue("key" in call, "$name: the list at line ${code.take(hit.range.first).count { it == '\n' } + 1} has a key")
            }
        }
    }

    @Test fun theResultPageUsesTheOrbitPiecesTheBoardNames() {
        val results = SourceContract.code(search("CosmoSearchResults"))
        for (needle in listOf(
            "OrbitBiteCard(", "OrbitPlayButton(", "coverFill(cover, title)", "OrbitLabel(\"Top result · \$kind\"", "OrbitArtistDisc(", "OrbitSongRow(", "OrbitSongRowFor(",
            "OrbitRecord(", "OrbitPortrait(", "CosmoIcons.Plus", "Res.string.playlist_action", "Res.string.cosmo_search_no_results_hint", "WIDE_SEARCH_DP",
            "Res.string.search_library_no_results", "Res.string.search_library_empty", "songListSkeleton(count = 3, keyPrefix = \"skeleton:search:more\"",
        )) assertTrue(needle in results, "CosmoSearchResults has $needle")
        assertEquals(840f, com.cosmotune.design.NowPlayingLayout.WIDE_MIN_DP, "the wide page starts where the wide Now Playing does")
        assertTrue("const val WIDE_SEARCH_DP = 840f" in results)
        val browse = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitBrowse.kt"))
        assertTrue("artistMotionPlays(animated, LocalReducedMotion.current, metered, overCellular)" in browse, "the motion portrait keeps the artist page's gating")
    }
}
