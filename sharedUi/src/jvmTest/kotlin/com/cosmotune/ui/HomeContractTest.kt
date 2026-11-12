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
            "onPlayPause: (() -> Unit)? = null",
            "playbackTime: (() -> Pair<Long, Long>)? = null",
        ), SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch"))
        assertEquals("playbackTime", SourceContract.names(SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch")).last())
    }

    @Test fun theSwitchEndsInTheOneOrbitCallAndPassesItEveryParameterByName() {
        val src = file("HomeSwitch.kt")
        // Optional switch-owned inputs enrich the shared feed; they are not screen callbacks.
        // State is deliberately transformed once and the result is what the screen gets.
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
        assertFalse("LocalCosmoShell" in body, "no question whether the look is on")
        assertFalse(Regex("""\bHomeScreen\(""").containsMatchIn(body), "the old screen is not called")
        val at = body.indexOf("OrbitHome(")
        assertTrue(at >= 0, "the Orbit screen is called")
        assertEquals(at, body.lastIndexOf("OrbitHome("), "once")
        val statement = SourceContract.firstStatement(body.substring(at))
        assertTrue(statement.endsWith(")"), "the whole call is read, to its closing parenthesis: $statement")
        assertEquals("$statement }", body.substring(at).replace(Regex("\\s+"), " "), "and it is the last statement")
        // Orbit names its own page and has no lead hero switch.
        SourceContract.assertPassesEveryParameter(statement, names - setOf("title", "leadHero"))
        assertTrue("state = shownState" in statement, "the screen receives the enriched feed")
        assertTrue(statement.contains("discoveryControls = controls"))
        assertTrue("quickLinks = quickLinks" in statement, "the screen draws the host's links")
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

    @Test fun theDesktopLoadsExploreWhileOnListenNowOrSearchAndOnlyWhenItIsStillLoading() {
        val src = SourceContract.code(SourceContract.read(desktopRoot))
        val at = src.indexOf("val homeWantsExplore =")
        assertTrue(at >= 0, "the extra loader exists")
        val block = src.substring(at, src.indexOf("LaunchedEffect(exploreState)", at))
        assertTrue("destination == DesktopDestination.LISTEN_NOW" in block && "destination == DesktopDestination.SEARCH" in block, "only on Listen now and on Search (its mood grid)")
        assertTrue("LaunchedEffect(exploreReloads) {" in block && "if (exploreReloads > 0 && homeWantsExplore) loadExplore()" in block, "Retry from either page reloads")
        assertTrue("val homeWantsExplore = destination == DesktopDestination.LISTEN_NOW || destination == DesktopDestination.SEARCH" in src, "on those two pages, whatever else")
        assertFalse("newLook" in block, "there is no look switch to ask")
        assertTrue("if (homeWantsExplore && exploreState is UiState.Loading) loadExplore()" in block, "once, while Explore has not arrived")
        // the original loader is as it was
        assertTrue(
            Regex("""LaunchedEffect\(destination, exploreReloads\) \{\s*if \(destination == DesktopDestination\.EXPLORE &&\s*\(exploreState is UiState\.Loading \|\| exploreReloads > 0\)\s*\) \{\s*loadExplore\(\)""").containsMatchIn(src),
            "the loader of the Explore page is untouched",
        )
    }

}
