package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (the phone's files live in `app/`): the two switches of the
 * phone chrome (the top bar and the profile button), and the six sites in `MainActivity` that put them, the drawer's five
 * entries, the dock's tabs with Search as the bar's button and the lifted fade band in place. The old bar and avatar button are
 * gone (`FrostedTopBar.kt` keeps only the bar's metrics), so what is pinned here is the seam: upstream's parameter lists, kept so
 * its call sites still merge, and the one new call with its arguments.
 */
class PhoneChromeContractTest {
    private val chromeDir = "app/src/main/java/com/cosmotune/ui/chrome/"
    private val frostedPath = "app/src/main/java/com/cosmotune/music/ui/components/FrostedTopBar.kt"
    private val mainPath = "app/src/main/java/com/cosmotune/music/MainActivity.kt"

    private val topBarSwitch get() = SourceContract.read("${chromeDir}TopBarSwitch.kt")
    private val profileSwitch get() = SourceContract.read("${chromeDir}ProfileButtonSwitch.kt")
    private val frosted get() = SourceContract.read(frostedPath)
    private val main get() = SourceContract.read(mainPath)
    private val mainCode get() = SourceContract.code(main)

    /** The text of the call or block that opens with the bracket at [open] in [text], brackets included. */
    private fun balanced(text: String, open: Int): String {
        val pair = mapOf('(' to ')', '{' to '}')
        val opener = text[open]
        val closer = pair.getValue(opener)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                opener -> depth++
                closer -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("the bracket at $open is never closed")
    }

    /** The arguments of the first call that starts with [head] in [text]: what lies between its brackets, white space collapsed, without a trailing comma. */
    private fun callArguments(text: String, head: String): String {
        val at = text.indexOf(head)
        assertTrue(at >= 0, "$head is in the text")
        val whole = balanced(text, at + head.length - 1)
        return squash(whole.substring(1, whole.length - 1)).removeSuffix(",").trim()
    }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

    // ---- the top bar switch -----------------------------------------------------------------------------------------------

    /** Upstream's `FrostedTopBar` parameter list, name for name and in its order (the old bar itself is gone). */
    private val frostedTopBarNames = listOf(
        "title", "scrolled", "modifier", "transparentBackdrop", "artworkPageChrome", "backButtonHazeState", "trailingTitle", "onBack",
        "refreshing", "pullFraction", "accessory", "actions",
    )

    @Test fun theTopBarSwitchKeepsUpstreamsFrostedTopBarParameterList() {
        val params = SourceContract.parameters(topBarSwitch, "TopBarSwitch")
        assertEquals(frostedTopBarNames, SourceContract.names(params))
        assertTrue("pullFraction: () -> Float = { 0f }" in params && "backButtonHazeState: HazeState? = null" in params, "with upstream's types and defaults: $params")
    }

    @Test fun theSwitchLivesInTheChromePackageOfTheAppAndIsTheOneCall() {
        val src = topBarSwitch
        assertTrue(src.contains("package com.cosmotune.ui.chrome"), "TopBarSwitch is in com.cosmotune.ui.chrome")
        val body = SourceContract.functionBody(src, "TopBarSwitch")
        val first = SourceContract.firstStatement(body)
        assertTrue(first.startsWith("CosmoTopBar("), "the first statement is the bar: $first")
        assertEquals("{ $first }", squash(body), "and it is the whole body")
        assertFalse("LocalCosmoShell" in SourceContract.code(src), "no question whether the look is on")
    }

    @Test fun theOldBarIsGoneAndItsFileKeepsOnlyTheMetrics() {
        val src = SourceContract.code(frosted)
        assertFalse(Regex("""\bfun FrostedTopBar\(""").containsMatchIn(src), "the old bar is deleted")
        assertFalse(Regex("""\bfun TopBarAccountButton\(""").containsMatchIn(src), "the old avatar button is deleted")
        for (metric in listOf("val TopBarContentHeight = 52.dp", "val TopBarContentGap = 12.dp", "fun topBarHeight(): Dp", "fun topBarContentPadding(): Dp")) {
            assertTrue(metric in src, "the bar's metrics stay: $metric")
        }
    }

    @Test fun theSwitchCallsCosmoTopBarWithTheBriefsArgumentsAndNoneOfTheMaterialOnes() {
        val body = SourceContract.functionBody(topBarSwitch, "TopBarSwitch")
        val call = callArguments(body, "CosmoTopBar(")
        assertEquals(
            "title = title, scrolled = scrolled, height = topBarHeight(), modifier = modifier, trailingTitle = trailingTitle, " +
                "onBack = onBack, refreshing = refreshing, pullFraction = pullFraction, accessory = accessory, actions = actions",
            call,
        )
        for (material in listOf("transparentBackdrop", "artworkPageChrome", "backButtonHazeState")) {
            assertFalse(call.contains(material), "$material means nothing in the new look and is not passed on")
        }
    }

    // ---- the profile button switch ----------------------------------------------------------------------------------------

    @Test fun theProfileSwitchTakesTheAccountButtonsParametersPlusTheEntries() {
        assertEquals(
            listOf(
                "account: Account?", "onClick: () -> Unit", "onSwipeProfile: ((forward: Boolean) -> Boolean)? = null", "modifier: Modifier = Modifier",
                "entries: List<CosmoDrawerEntry> = emptyList()",
            ),
            SourceContract.parameters(profileSwitch, "ProfileButtonSwitch"),
            "upstream's TopBarAccountButton list, then the drawer's entries",
        )
    }

    @Test fun theProfileSwitchIsTheOneCallOfTheNewButton() {
        val src = profileSwitch
        assertTrue(src.contains("package com.cosmotune.ui.chrome"), "ProfileButtonSwitch is in com.cosmotune.ui.chrome")
        val body = SourceContract.functionBody(src, "ProfileButtonSwitch")
        val first = SourceContract.firstStatement(body)
        assertTrue(first.startsWith("CosmoProfileButton("), "the first statement is the new button: $first")
        assertEquals("{ $first }", squash(body), "and it is the whole body")
        assertFalse("TopBarAccountButton(" in SourceContract.code(src) || "LocalCosmoShell" in SourceContract.code(src), "no old button, no question whether the look is on")
    }

    @Test fun theNewButtonGetsTheAvatarTheNameWithSignInAsItsFallbackAndTheEntries() {
        val body = SourceContract.functionBody(profileSwitch, "ProfileButtonSwitch")
        assertEquals(
            "avatarUrl = account?.thumbnailUrl, name = account?.name ?: stringResource(R.string.sign_in), " +
                "subtitle = account?.email?.takeIf { it.isNotBlank() }, entries = entries, onAccount = onClick, modifier = modifier",
            callArguments(body, "CosmoProfileButton("),
        )
    }

    // ---- MainActivity: the two calls --------------------------------------------------------------------------------------

    @Test fun mainActivityNoLongerCallsTheOldBarOrTheOldAvatarButtonDirectly() {
        val src = mainCode
        assertFalse(Regex("""\bFrostedTopBar\(""").containsMatchIn(src), "no direct call of FrostedTopBar(")
        assertFalse(Regex("""\bTopBarAccountButton\(""").containsMatchIn(src), "no direct call of TopBarAccountButton(")
        assertEquals(1, Regex("""\bcom\.cosmotune\.ui\.chrome\.TopBarSwitch\(""").findAll(src).count(), "one call of the bar's switch, fully qualified")
        assertEquals(1, Regex("""\bcom\.cosmotune\.ui\.chrome\.ProfileButtonSwitch\(""").findAll(src).count(), "one call of the button's switch, fully qualified")
        assertTrue(src.contains("                com.cosmotune.ui.chrome.TopBarSwitch(\n                    title = when {"), "the bar's call keeps its arguments and its place")
    }

    @Test fun theBarCallKeepsEveryArgumentItHadIncludingTheMaterialOnes() {
        val call = callArguments(mainCode, "com.cosmotune.ui.chrome.TopBarSwitch(")
        val names = listOf(
            "title", "transparentBackdrop", "artworkPageChrome", "backButtonHazeState", "trailingTitle", "scrolled", "refreshing",
            "pullFraction", "onBack", "modifier", "actions",
        )
        for (name in names) assertTrue(Regex("""\b$name = """).containsMatchIn(call), "the call still passes $name (the switch decides what the new bar needs)")
        // the one argument that went with the old look: the Search tab's field in the bar (the page draws its own pill now)
        assertFalse(Regex("""\baccessory = """).containsMatchIn(call), "the old look's search field is no longer the bar's accessory")
        assertEquals((names + "accessory").sorted(), frostedTopBarNames.sorted(), "and those, with the accessory left at its default, are all of upstream's bar's parameters")
    }

    @Test fun theProfileButtonCallPassesSixDrawerEntriesAndTheOldArguments() {
        val call = callArguments(mainCode, "com.cosmotune.ui.chrome.ProfileButtonSwitch(")
        assertTrue(call.contains("account = account,"), "account")
        assertTrue(call.contains("onSwipeProfile = { forward -> viewModel.stepProfile(forward) },"), "upstream's swipe argument stays, so its call site still merges")
        assertTrue(call.contains("if (signedIn) {") && call.contains("viewModel.loadChannels()") && call.contains("showAccountSelector = true") && call.contains("else showSettings = true"), "the head's tap is the old avatar tap")
        assertTrue(call.contains("entries = listOf("), "the entries come as a list")
        assertEquals(6, Regex("""\bcom\.cosmotune\.ui\.chrome\.CosmoDrawerEntry\(""").findAll(call).count(), "six entries, fully qualified")
        assertEquals(6, Regex("""\bCosmoDrawerEntry\(""").findAll(mainCode).count(), "and no other entry anywhere in the file")
        // The root draws one page by the order of its `when`: an entry must leave History, a Library "Show all", Discord and
        // Spotify first, or it would only close the drawer.
        assertEquals(5, Regex("""\bleavePages\(\)""").findAll(call).count(), "every entry leaves those pages first")
        for (flag in listOf("showHistory = false", "libraryShowAll = null", "showDiscord = false", "showSpotify = false")) {
            assertTrue(mainCode.contains("val leavePages = {") && mainCode.substringAfter("val leavePages = {").substringBefore("}").contains(flag), "leavePages resets $flag")
        }
    }

    @Test fun listeningToolsPrecedesTheExistingFiveEntriesWithoutChangingTheirActions() {
        val call = callArguments(mainCode, "com.cosmotune.ui.chrome.ProfileButtonSwitch(")
        val list = callArguments(call.substringAfter("entries = listOf"), "(")
        val parts = list.split("com.cosmotune.ui.chrome.CosmoDrawerEntry(").drop(1).map { squash(it) }
        assertEquals(6, parts.size)
        assertTrue("listeningToolsOpen = true" in parts.first(), "listening tools opens over the current page")
        val (replay, together, downloads, equaliser, settings) = parts.drop(1)
        assertTrue(replay.startsWith("icon = CosmoIcons.Replay, label = stringResource(R.string.replay),"), replay)
        assertTrue("replayLandingPage = ReplayStoryPage.INTRO" in replay && "showReplay = true" in replay, "Replay lands on its intro: $replay")
        assertTrue(together.startsWith("icon = CosmoIcons.Together, label = stringResource(R.string.listen_together),"), together)
        assertTrue("showSettings = true" in together && "showListenTogether = true" in together, "Listen Together as the player does it: $together")
        assertFalse("dismissPlayer" in together, "the player is not open here: nothing to dismiss")
        assertTrue(downloads.startsWith("icon = CosmoIcons.Download, label = stringResource(R.string.downloads),"), downloads)
        val flat = downloads.filterNot { it.isWhitespace() }
        assertTrue(
            "onLibraryItemClick(ShelfItem(title=context.getString(R.string.downloads),subtitle=context.getString(R.string.downloaded_songs)," +
                "thumbnailUrl=null,videoId=null,browseId=\"local:downloads\"" in flat,
            "Downloads is the Library's row, through the same handler (the permission request included): $downloads",
        )
        assertTrue(equaliser.startsWith("icon = CosmoIcons.Equaliser, label = stringResource(R.string.equalizer),"), equaliser)
        assertTrue(
            "showSettings = true" in equaliser && "settingsSubScreen = \"equalizer\"" in equaliser && "showEqualizer = true" in equaliser &&
                equaliser.indexOf("showSettings = true") < equaliser.indexOf("settingsSubScreen = \"equalizer\""),
            "the Equaliser opens as the Settings call's onEqualizer does, after showSettings: $equaliser",
        )
        assertTrue(settings.startsWith("icon = CosmoIcons.Settings, label = stringResource(R.string.settings),"), settings)
        assertTrue("showSettings = true" in settings, settings)
    }

    // ---- MainActivity: the lifted fade, the tabs and the redirect ----------------------------------------------------------

    @Test fun theTopFadeIsTheCoverGroundOnEveryPageNotOnlyOnTabs() {
        val text = main
        assertTrue(
            "pageColor = Color(cosmoShell.colors.palette.backgroundTop)," in text,
            "the new album and artist pages draw no artwork ground, so the top fade is the cover ground there too",
        )
        assertFalse("cosmoShell != null && !isDetailVisible" in text, "the detail-page exception is gone")
    }

    @Test fun theFadeBandUnderTheCapsulesIsGoneAndTheTapGuardStays() {
        val text = main
        assertEquals(1, Regex("""\bBottomFadeScrim\(""").findAll(mainCode).count(), "the top fade is the one call left: the bottom fade went with the old bars")
        assertFalse("withMiniPlayer = player.song != null" in mainCode, "the bottom fade's call is gone")
        assertTrue("FloatingBarsTapGuard(\n                    barsHeight = { floatingBarsHeight.intValue }," in text, "the tap guard stays, it reads the chrome's height")
    }

    @Test fun theDockHasFourTabsHomeExploreReleasesLibraryAndSearchIsTheBarsButton() {
        val chrome = main.substringAfter("// The floating chrome (tab capsule and mini capsule).").substringBefore("selectedIndex = ")
        assertTrue(chrome.length < main.length, "the chrome's comment is found")
        assertEquals(4, Regex("""\bCosmoTab\(""").findAll(chrome).count(), "four tabs")
        assertTrue(
            "CosmoTab(homeLabel, CosmoIcons.Home, CosmoIcons.HomeFill), CosmoTab(exploreLabel, CosmoIcons.Explore, CosmoIcons.ExploreFill), " +
                "CosmoTab(releasesLabel, CosmoIcons.Bell, CosmoIcons.BellFill), CosmoTab(libraryLabel, CosmoIcons.Library, CosmoIcons.LibraryFill)" in squash(chrome),
            "Home, Explore, Releases, Library, in that order",
        )
        assertFalse("searchLabel" in chrome, "no Search tab in the dock")
        assertEquals(1, Regex("""\bCosmoTab\(homeLabel""").findAll(mainCode).count(), "and no second definition elsewhere")
        assertTrue("// DESIGN_SYSTEM.md 5.2: Search is not a tab, it is the top bar's button" in main, "the comment says why")
        assertFalse("// the order of TAB_HOME, TAB_EXPLORE, TAB_LIBRARY, TAB_SEARCH" in main, "the old comment is gone")
        assertTrue("IconButton(" in main && "Icon(CosmoIcons.Search, contentDescription = searchLabel" in main, "Search is a button in the top bar's actions")
    }

    @Test fun theDockMapsItsTabsAndBackAndSearchKeepsTheTabItWasOpenedFromLit() {
        val text = main
        assertTrue("private val MUSIC_DOCK = listOf(TAB_HOME, TAB_EXPLORE, TAB_RELEASES, TAB_LIBRARY)" in text, "the dock's order, in the host's numbers")
        assertTrue("selectedIndex = if (selectedTab == TAB_SEARCH || showSettings) -1 else MUSIC_DOCK.indexOf(selectedTab)," in text, "Search and Settings light no tab")
        assertTrue("onTabSelected = { index -> onTabSelected(MUSIC_DOCK[index]) }," in text, "the one tab handler is the root's")
        assertTrue("LaunchedEffect(selectedTab) { if (selectedTab != TAB_SEARCH) searchReturnTab = selectedTab }" in text, "the tab Search returns to")
        assertTrue("selectedTab == TAB_SEARCH -> ({ selectedTab = searchReturnTab })" in text, "the bar's back button returns there")
        assertTrue("selectedTab = if (selectedTab == TAB_SEARCH) searchReturnTab else TAB_HOME" in text, "and so does the system's")
    }

    @Test fun theTabConstantsAreUntouched() {
        val text = main
        for (line in listOf("private const val TAB_HOME = 0", "private const val TAB_EXPLORE = 1", "private const val TAB_LIBRARY = 2", "private const val TAB_SEARCH = 3", "private const val TAB_RELEASES = 4")) {
            assertTrue(line in text, "$line stays (decision E4)")
        }
    }

    @Test fun theTabWithoutADoorIsDecidedWhereItIsRead() {
        // P4-20: this was a SideEffect that moved the saved tab to Search once Explore had composed (one frame of Explore). The tab the
        // page reads is now derived where it is read (`ShownTab` over `TabRedirect.shown`), so no frame composes Explore first; the
        // derivation is pinned by PolishContractTest and its rule by TabRedirectTest.
        val text = main
        assertFalse("androidx.compose.runtime.SideEffect { selectedTab = TAB_SEARCH }" in text, "no correction after the frame")
        assertEquals(1, Regex(Regex.escape("com.cosmotune.design.TabRedirect.shown(")).findAll(text).count(), "one derivation")
        assertTrue(
            "com.cosmotune.design.TabRedirect.shown( stored, homeTab = TAB_HOME, releasesTab = TAB_RELEASES, )" in squash(text),
            "the derivation has no look to ask about",
        )
        assertFalse("newLook" in text, "the New look switch is gone")
        assertTrue(text.indexOf("val onTabSelected: (Int) -> Unit = { index ->") > 0, "the one tab handler is still there")
    }

    // ---- the upstream bars and the log ---------------------------------------------------------------------------------------

    @Test fun theUpstreamBarsAreGoneAndTheChromeIsTheOnlyOne() {
        val src = frosted
        // The file's own package is `com.cosmotune.music` since the rename (FORK.md); any other `com.cosmotune` is ours.
        assertFalse(Regex("""com\.cosmotune(?!\.music\b)""").containsMatchIn(src), "the metrics left in the old bar's file need nothing of ours")
        assertTrue("com.cosmotune.ui.shell.CosmoBottomChrome(" in mainCode, "the chrome is the bottom bar")
        for (old in listOf("cosmoShell != null", "glassActive", "FloatingBottomBar(", "GlassNavBar(", "MiniPlayer(")) assertFalse(old in mainCode, "$old is gone")
    }

    @Test fun theForkLogHasOneRowForTheSixSitesWithFourColumns() {
        val rows = SourceContract.read("FORK.md").lines().filter { it.startsWith("| ") && "TopBarSwitch" in it && "MainActivity.kt" in it }
        assertEquals(1, rows.size, "one new row for the six sites")
        val row = rows.single()
        assertEquals(5, row.count { it == '|' }, "four columns (no pipe inside a cell): $row")
        for (word in listOf("ProfileButtonSwitch", "BottomFadeScrim", "SideEffect", "CosmoTab", "pageColor")) {
            assertTrue(word in row, "the row names $word")
        }
    }

}
