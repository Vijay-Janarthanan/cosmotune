package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to be
 * added): the seam of Home's switch, the two call sites in the roots, the load-more rule copied from the old screen, the draw-phase
 * rule for the live palette, the keys of the list, and the numbers of the hero and the mood row.
 */
class HomeContractTest {
    private val home = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/home"
    private val oldScreenPath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/screens/HomeScreen.kt"
    private val phoneRoot = "app/src/main/java/com/cosmotune/music/MainActivity.kt"
    private val desktopRoot = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"

    private fun file(name: String): String = SourceContract.read("$home/$name")
    private fun code(name: String): String = SourceContract.code(file(name))

    private val oldList get() = SourceContract.parameters(SourceContract.read(oldScreenPath), "HomeScreen")
    private val browseParameter = "browse: CosmoBrowse? = null"

    // ---- the seam ---------------------------------------------------------------------------------------------------------------

    @Test fun theSwitchTakesHomeScreensParametersNameForNameAndTypeForTypeAndThenBrowse() {
        val old = oldList
        assertTrue(old.size >= 19, "the old screen's list was read (${old.size})")
        assertEquals(oldList.filterNot { it.startsWith("discoveryControls:") } + browseParameter + listOf(
            "playlistSongs: List<Song> = emptyList()",
            "saveGeneratedPlaylist: (suspend (String, List<Song>) -> Unit)? = null",
            "discoveryHistory: List<Song> = emptyList()",
            "discoveryLikedIds: Set<String> = emptySet()",
            "followedArtists: List<com.cosmotune.listening.WatchedArtist> = emptyList()",
            "quickLinks: List<HomeQuickLink> = emptyList()",
        ), SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch"))
        assertEquals("quickLinks", SourceContract.names(SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch")).last())
    }

    @Test fun theNewScreenTakesTheSameList() {
        assertEquals(oldList + browseParameter + "quickLinks: List<HomeQuickLink> = emptyList()", SourceContract.parameters(file("CosmoHome.kt"), "CosmoHome"))
    }

    @Test fun bothBranchesOfTheSwitchPassEveryParameterByNameAndTheOldOneDoesNotPassBrowse() {
        val src = file("HomeSwitch.kt")
        // Optional switch-owned inputs enrich the shared feed; they are not screen callbacks.
        // State is deliberately transformed once and the same result must reach both branches.
        val names = SourceContract.names(SourceContract.parameters(src, "HomeSwitch")) - setOf(
            "state", "playlistSongs", "saveGeneratedPlaylist", "discoveryHistory", "discoveryLikedIds", "followedArtists", "quickLinks",
        )
        val body = SourceContract.functionBody(src, "HomeSwitch")
        assertTrue(body.contains("HomeDiscoveryControls(onRetry,"), "changing discovery resets old regional shelves")
        assertTrue(body.contains("PersonalPlaylistDialog("), "the switch owns the optional playlist preview")
        assertTrue("MusicDiscoveryContext(playlistSongs, discoveryHistory, discoveryLikedIds, followedArtists)" in body,
            "all optional discovery inputs contribute to the shared candidate context")
        val enrichedState = body.substringAfter("val shownState = remember(state, personalShelves)").substringBefore("var playlistOpen")
        assertTrue("if (personalShelves.isEmpty()) state" in enrichedState,
            "without local suggestions the original loading/error/success state is retained")
        assertTrue("(state as? UiState.Success)?.data.orEmpty()" in enrichedState &&
            "current.filterNot { it.sourceId in sources } + personalShelves" in enrichedState,
            "enrichment preserves the online feed and replaces only the local shelf identities")
        val statement = SourceContract.firstStatement(body.substring(body.indexOf("if (LocalCosmoShell.current != null)")))
        assertTrue(statement.startsWith("if (LocalCosmoShell.current != null) {"), "the switch is its one statement: $statement")
        assertTrue(statement.endsWith("}"), "and the whole if/else is read, to its closing brace")
        val newBranch = statement.substringBefore("} else {")
        val oldBranch = statement.substringAfter("} else {")
        assertTrue(newBranch.contains("CosmoHome("), "look on: the new screen")
        assertTrue(oldBranch.contains("HomeScreen("), "look off: the old screen")
        SourceContract.assertPassesEveryParameter(newBranch, names)
        SourceContract.assertPassesEveryParameter(oldBranch, names - "browse")
        assertTrue("state = shownState" in newBranch && "state = shownState" in oldBranch,
            "both screens receive the same meaningfully enriched feed, including the legacy look")
        assertTrue(newBranch.contains("discoveryControls = controls"))
        assertTrue(oldBranch.contains("discoveryControls = controls"))
        assertFalse(Regex("""\bbrowse\b""").containsMatchIn(oldBranch), "the old screen has no browse")
        assertTrue("quickLinks = quickLinks" in newBranch, "the new screen draws the host's links")
        assertFalse("quickLinks" in oldBranch, "the old screen has none")
    }

    // ---- the call sites -----------------------------------------------------------------------------------------------------------

    /** The text of the call whose name starts at [start]: from there to the parenthesis that closes its argument list. */
    private fun callFrom(src: String, start: Int): String {
        val open = src.indexOf('(', start)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return src.substring(start, i + 1)
            }
        }
        error("the call at $start is never closed")
    }

    @Test fun thePhoneCallsTheSwitchWithItsBrowseArgumentAfterTheTitle() {
        val src = SourceContract.code(SourceContract.read(phoneRoot))
        assertFalse(Regex("""\bHomeScreen\(""").containsMatchIn(src), "the root no longer calls the old screen directly")
        val at = src.indexOf("TAB_HOME -> com.cosmotune.ui.home.HomeSwitch(")
        assertTrue(at >= 0, "the Home tab calls the switch, fully qualified")
        assertEquals(at, src.lastIndexOf("TAB_HOME -> com.cosmotune.ui.home.HomeSwitch("), "once")
        val call = callFrom(src, at)
        val browse = "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { viewModel.openMoodGenre(it); selectedTab = TAB_EXPLORE }, onRetry = viewModel::loadExplore),"
        assertTrue(browse in call, "the mood page lives under the Explore tab (decision E4)")
        val title = call.indexOf("title = stringResource(R.string.listen_now),")
        assertTrue(title in 0 until call.indexOf(browse), "browse comes right after the title, well away from the arguments that end the call")
        assertTrue(call.indexOf(browse) < call.indexOf("recentlyPlayedLoading ="), "and before recentlyPlayedLoading, which sits two lines from the mood page's site")
    }

    @Test fun theDesktopCallsTheSwitchWithItsBrowseArgumentAfterTheTitleAndOpensTheMoodThroughExplore() {
        val src = SourceContract.code(SourceContract.read(desktopRoot))
        assertFalse(Regex("""\bHomeScreen\(""").containsMatchIn(src), "the root no longer calls the old screen directly")
        val at = src.indexOf("destination == DesktopDestination.LISTEN_NOW -> com.cosmotune.ui.home.HomeSwitch(")
        assertTrue(at >= 0, "the Listen now page calls the switch, fully qualified")
        val call = callFrom(src, at)
        val browse = "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { selectDestination(DesktopDestination.EXPLORE); openMoodGenre(it) }, onRetry = { exploreReloads++ }),"
        assertTrue(browse in call, "selectDestination clears the chosen mood, so it comes before openMoodGenre")
        assertTrue(call.indexOf("title = null,") in 0 until call.indexOf(browse))
        assertTrue(call.indexOf(browse) < call.indexOf("leadHero = false,"), "well before leadHero, which sits two lines from the mood page's site")
    }

    @Test fun theDesktopLoadsExploreWhileOnListenNowWithTheNewLookOnlyAndOnlyWhenItIsStillLoading() {
        val src = SourceContract.code(SourceContract.read(desktopRoot))
        val at = src.indexOf("val homeWantsExplore =")
        assertTrue(at >= 0, "the extra loader exists")
        val block = src.substring(at, src.indexOf("LaunchedEffect(exploreState)", at))
        assertTrue("destination == DesktopDestination.LISTEN_NOW" in block && "destination == DesktopDestination.SEARCH" in block, "only on Listen now and on Search (its mood grid)")
        assertTrue("LaunchedEffect(exploreReloads) {" in block && "if (exploreReloads > 0 && homeWantsExplore) loadExplore()" in block, "Retry from either page reloads")
        assertTrue("rememberCosmoLookSettings().newLook" in block, "only while the new look is on: with it off the app loads what it loaded before")
        assertTrue("if (homeWantsExplore && exploreState is UiState.Loading) loadExplore()" in block, "once, while Explore has not arrived")
        // the original loader is as it was
        assertTrue(
            Regex("""LaunchedEffect\(destination, exploreReloads\) \{\s*if \(destination == DesktopDestination\.EXPLORE &&\s*\(exploreState is UiState\.Loading \|\| exploreReloads > 0\)\s*\) \{\s*loadExplore\(\)""").containsMatchIn(src),
            "the loader of the Explore page is untouched",
        )
    }

    // ---- the load-more rule ---------------------------------------------------------------------------------------------------------

    private val loadMoreCondition = "if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()"

    @Test fun theLoadMoreConditionIsTheOldScreensAndIsInCosmoHome() {
        assertTrue(loadMoreCondition in code("CosmoHome.kt"), "CosmoHome checks the tail the way HomeScreen does")
        assertTrue(loadMoreCondition in SourceContract.code(SourceContract.read(oldScreenPath)), "and the old screen still says it that way")
        val src = code("CosmoHome.kt")
        assertTrue("LaunchedEffect(listState, loadingMore)" in src, "restarted on loadingMore, so the next page is asked for when one settles")
        assertTrue("val layout = listState.layoutInfo" in src && "(layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount" in src, "the last visible index and the total are read in a snapshot flow")
        assertTrue(file("CosmoHome.kt").contains("HomeScreen's load-more effect"), "and the file names the source it was copied from")
        assertTrue("if (onLoadMore != null && state is UiState.Success)" in src, "only for a loaded feed with a loader")
    }

    // ---- the draw-phase rule --------------------------------------------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
    private fun enclosingHeads(text: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += text.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    // a draw lambda, a layer block (the shadow's colours are read in one), or the colour lambdas the shadow takes
    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$|\b(spot|ambient)\s*=\s*$""")

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        for (name in listOf("HomeSwitch.kt", "CosmoHome.kt")) {
            val text = code(name)
            var from = 0
            while (true) {
                val at = text.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) continue
                assertTrue(
                    enclosingHeads(text, at).any { drawHead.containsMatchIn(it) },
                    "$name: live( at line ${text.take(at).count { it == '\n' } + 1} is not in a draw lambda or a layer block",
                )
            }
        }
    }

    @Test fun noBrushIsRememberedNoColourIsPickedByHandAndOnlyWhiteAndBlackAreNamed() {
        for (name in listOf("HomeSwitch.kt", "CosmoHome.kt")) {
            val text = code(name)
            assertFalse(Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient)""").containsMatchIn(text), "$name remembers a brush or a live colour")
            assertFalse(Regex("""Color\(0x""").containsMatchIn(text), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b""").containsMatchIn(text), "$name names a colour")
            assertFalse(Regex("""Color\.(White|Black)""").containsMatchIn(text), "$name uses no fixed colour: there is no scrim on Home any more")
        }
    }

    // ---- the list ---------------------------------------------------------------------------------------------------------------------

    @Test fun everyItemOfTheListHasAKey() {
        val text = code("CosmoHome.kt")
        val calls = Regex("""\b(item|items|itemsIndexed)\(""").findAll(text).toList()
        assertTrue(calls.size >= 8, "the list's items were found (${calls.size})")
        for (call in calls) {
            val args = callFrom(text, call.range.first)
            assertTrue("key =" in args, "$call at line ${text.take(call.range.first).count { it == '\n' } + 1} has a key: ${args.take(60)}")
        }
        assertFalse(Regex("""\bitem\s*\{""").containsMatchIn(text), "no item without its key")
        assertTrue("item(key = shelf.title + index)" in text, "the shelves keep the old screen's keys")
    }

    // ---- the page -----------------------------------------------------------------------------------------------------------------------

    @Test fun theHeadingNamesThePlace() {
        val text = code("CosmoHome.kt")
        // DESIGN_SYSTEM.md R3: the title names the place, not a greeting.
        assertTrue("CosmoPageHeader(title = stringResource(Res.string.cosmo_home)" in text)
        assertFalse("subtitle =" in text.substringAfter("home:header").substringBefore("home:sign-in"), "no subtitle")
        for (key in listOf("cosmo_home_morning", "cosmo_home_afternoon", "cosmo_home_evening", "cosmo_home_late")) assertFalse(key in text, key)
        assertFalse("HOME_TITLE_LIFT" in text, "the page's first row sits where the other tabs' does")
    }

    @Test fun theMoodRowStandsLowerDownAndIsHiddenWhenThereIsNothingToShow() {
        val text = code("CosmoHome.kt")
        assertTrue("BrowseSplit.moods(" in text, "the chips are the first section of Explore")
        assertTrue(Regex("""KitChipRow\(\s*labels = moods\.map \{ it\.title \},\s*selectedIndex = -1,\s*onSelect = \{ onOpen\(moods\[it\]\) \},""").containsMatchIn(text), "none selected; a tap opens the mood")
        assertTrue("if (moods.isNotEmpty() && browse != null)" in text, "an error, an empty list, no browse or a list still loading hides the row")
        assertTrue("stringResource(Res.string.cosmo_moods)" in text, "the row is headed cosmo_moods")
        // DESIGN_SYSTEM.md R4: never the first thing on the page.
        assertTrue("private const val LOWER_AFTER_SHELVES = 3" in text && "if (index == LOWER_AFTER_SHELVES) lower()" in text && "if (state.data.size <= LOWER_AFTER_SHELVES) lower()" in text, "under the third shelf, or at the foot of a short feed")
    }

    @Test fun theQuickTilesAndTheShelvesAreAsTheBlueprintSays() {
        val text = code("CosmoHome.kt")
        assertTrue("HomePlan.quickItems(state.data, HomePlan.QUICK_MAX - quickLinks.size)" in text, "the quick-access grid holds the host's links and the feed's first items")
        assertTrue("KitQuickTile(" in text && "tiles.chunked(columns)" in text, "in columns")
        assertTrue("onLongClick = onItemLongPress?.let { held -> { held(item) } }" in text, "a hold on a tile opens the item's menu")
        assertTrue("CosmoShelf(" in text && "onItemClick = { onItemClick(it, shelf.title) }" in text && "onItemLongPress = onItemLongPress" in text, "a shelf is a CosmoShelf")
        assertFalse("leadFirst" in text, "the shelf's defaults: every card the same square")
        assertFalse("HomeBento" in text || "HomeHero" in text, "no bento, no hero (DESIGN_SYSTEM.md R5, R6)")
        // A long list of songs: pages of four list rows, cover leading, the row's width less 48 dp
        assertTrue("HomePlan.isSongPages(shelf)" in text && "SONGS_PER_PAGE = 4" in text && "SONG_PAGE_INSET = 48.dp" in text && "CosmoRowLeading.Cover(item.thumbnailUrl)" in text)
        // states, as the old screen chooses them
        assertTrue("recentlyPlayedSkeleton(" in text && "feedSkeleton(firstIsHero = false)" in text && "feedMoreSkeleton()" in text)
        assertTrue("MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)" in text)
        assertTrue("SignInBanner(onSignIn = signIn," in text && "if (!signedIn && signIn != null)" in text)
        assertTrue("PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier)" in text)
    }

    @Test fun aQuickTileHasTheBlueprintsNumbersAndIsAButton() {
        val home = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/kit/KitCards.kt")
        val tile = SourceContract.code(SourceContract.functionBody(home, "KitQuickTile"))
        assertTrue("height(56.dp)" in tile && "RoundedCornerShape(CosmoRadii.rMd)" in tile, "56 dp, the token's corners")
        assertTrue("palette.surface" in tile, "on surface1, no gradient")
        assertTrue("combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)" in tile, "a tap plays or opens, a hold opens the menu")
    }

    @Test fun aWidePageMovesInByItsSizeClass() {
        val text = code("CosmoHome.kt")
        assertTrue("maxWidth >= EXPANDED_DP -> 12.dp" in text, "the margin of an expanded window is the 16 + 12 of the desktop's pages")
        assertTrue("maxWidth >= LARGE_DP -> 4" in text && "maxWidth >= MEDIUM_DP -> 3" in text, "and the quick grid takes more columns")
    }
}
