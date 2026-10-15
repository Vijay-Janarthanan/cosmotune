package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (the phone's files live in `app/`): the two switches of the
 * phone chrome (the top bar and the profile button), and the six sites in `MainActivity` that put them, the drawer's five
 * entries, the three tabs with Explore inside Search and the lifted fade band in place. With the new look off every site takes
 * the old branch, so what is pinned here is the seam: the same parameter lists, every argument by name, the old call kept.
 */
class PhoneChromeContractTest {
    private val chromeDir = "app/src/main/java/com/cosmotune/ui/chrome/"
    private val frostedPath = "app/src/main/java/com/music/bitchord/ui/components/FrostedTopBar.kt"
    private val mainPath = "app/src/main/java/com/music/bitchord/MainActivity.kt"

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

    @Test fun theTopBarSwitchTakesFrostedTopBarsParametersNameForNameAndTypeForType() {
        val old = SourceContract.parameters(frosted, "FrostedTopBar")
        assertEquals(12, old.size, "the old bar's list was read: $old")
        assertEquals(old, SourceContract.parameters(topBarSwitch, "TopBarSwitch"))
    }

    @Test fun theSwitchLivesInTheChromePackageOfTheAppAndAsksTheShell() {
        val src = topBarSwitch
        assertTrue(src.contains("package com.cosmotune.ui.chrome"), "TopBarSwitch is in com.cosmotune.ui.chrome")
        val body = SourceContract.functionBody(src, "TopBarSwitch")
        assertTrue(
            SourceContract.firstStatement(body).startsWith("if (LocalCosmoShell.current != null) {"),
            "the first statement asks whether the new look is on",
        )
    }

    @Test fun theOldBranchPassesEveryParameterByNameToFrostedTopBar() {
        val body = SourceContract.functionBody(topBarSwitch, "TopBarSwitch")
        val oldBranch = body.substringAfter("} else {")
        assertTrue(oldBranch.contains("FrostedTopBar("), "the else branch is the old bar")
        SourceContract.assertPassesEveryParameter(oldBranch, SourceContract.names(SourceContract.parameters(frosted, "FrostedTopBar")))
    }

    @Test fun theNewBranchCallsCosmoTopBarWithTheBriefsArgumentsAndNoneOfTheMaterialOnes() {
        val body = SourceContract.functionBody(topBarSwitch, "TopBarSwitch")
        val newBranch = body.substringBefore("} else {")
        assertTrue(newBranch.contains("CosmoTopBar("), "the if branch is the new bar")
        val call = callArguments(newBranch, "CosmoTopBar(")
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
        val old = SourceContract.parameters(frosted, "TopBarAccountButton")
        assertEquals(4, old.size, "the old button's list was read: $old")
        assertEquals(old + "entries: List<CosmoDrawerEntry> = emptyList()", SourceContract.parameters(profileSwitch, "ProfileButtonSwitch"))
    }

    @Test fun theProfileSwitchAsksTheShellAndKeepsTheOldButtonAsItsElseBranch() {
        val src = profileSwitch
        assertTrue(src.contains("package com.cosmotune.ui.chrome"), "ProfileButtonSwitch is in com.cosmotune.ui.chrome")
        val body = SourceContract.functionBody(src, "ProfileButtonSwitch")
        assertTrue(SourceContract.firstStatement(body).startsWith("if (LocalCosmoShell.current != null) {"), "the first statement asks whether the new look is on")
        val oldBranch = body.substringAfter("} else {")
        assertTrue(oldBranch.contains("TopBarAccountButton("), "the else branch is the old button")
        assertEquals(
            "account = account, onClick = onClick, onSwipeProfile = onSwipeProfile, modifier = modifier",
            callArguments(oldBranch, "TopBarAccountButton("),
            "the old call passes its four parameters by name and nothing else",
        )
    }

    @Test fun theNewButtonGetsTheAvatarTheNameWithSignInAsItsFallbackAndTheEntries() {
        val body = SourceContract.functionBody(profileSwitch, "ProfileButtonSwitch")
        val newBranch = body.substringBefore("} else {")
        assertTrue(newBranch.contains("CosmoProfileButton("), "the if branch is the new button")
        assertEquals(
            "avatarUrl = account?.thumbnailUrl, name = account?.name ?: stringResource(R.string.sign_in), " +
                "subtitle = account?.email?.takeIf { it.isNotBlank() }, entries = entries, onAccount = onClick, modifier = modifier",
            callArguments(newBranch, "CosmoProfileButton("),
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
            "pullFraction", "onBack", "modifier", "accessory", "actions",
        )
        for (name in names) assertTrue(Regex("""\b$name = """).containsMatchIn(call), "the call still passes $name (the switch decides what the new bar needs)")
        assertEquals(names.sorted(), SourceContract.names(SourceContract.parameters(frosted, "FrostedTopBar")).sorted(), "and those are all of the old bar's parameters")
    }

    @Test fun theProfileButtonCallPassesFiveDrawerEntriesAndTheOldArguments() {
        val call = callArguments(mainCode, "com.cosmotune.ui.chrome.ProfileButtonSwitch(")
        assertTrue(call.contains("account = account,"), "account")
        assertTrue(call.contains("onSwipeProfile = { forward -> viewModel.stepProfile(forward) },"), "the old swipe stays, the old branch still uses it")
        assertTrue(call.contains("if (signedIn) {") && call.contains("viewModel.loadChannels()") && call.contains("showAccountSelector = true") && call.contains("else showSettings = true"), "the head's tap is the old avatar tap")
        assertTrue(call.contains("entries = listOf("), "the entries come as a list")
        assertEquals(5, Regex("""\bcom\.cosmotune\.ui\.chrome\.CosmoDrawerEntry\(""").findAll(call).count(), "five entries, fully qualified")
        assertEquals(5, Regex("""\bCosmoDrawerEntry\(""").findAll(mainCode).count(), "and no other entry anywhere in the file")
        // The root draws one page by the order of its `when`: an entry must leave History, a Library "Show all", Discord and
        // Spotify first, or it would only close the drawer.
        assertEquals(5, Regex("""\bleavePages\(\)""").findAll(call).count(), "every entry leaves those pages first")
        for (flag in listOf("showHistory = false", "libraryShowAll = null", "showDiscord = false", "showSpotify = false")) {
            assertTrue(mainCode.contains("val leavePages = {") && mainCode.substringAfter("val leavePages = {").substringBefore("}").contains(flag), "leavePages resets $flag")
        }
    }

    @Test fun theFiveEntriesAreReplayListenTogetherDownloadsEqualiserSettingsInThatOrderWithTheStatementsTheRootAlreadyRuns() {
        val call = callArguments(mainCode, "com.cosmotune.ui.chrome.ProfileButtonSwitch(")
        val list = callArguments(call.substringAfter("entries = listOf"), "(")
        val parts = list.split("com.cosmotune.ui.chrome.CosmoDrawerEntry(").drop(1).map { squash(it) }
        assertEquals(5, parts.size)
        val (replay, together, downloads, equaliser, settings) = parts
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
            "pageColor = if (cosmoShell != null) Color(cosmoShell.colors.palette.backgroundTop) else chromePageColor," in text,
            "the new album and artist pages draw no artwork ground, so the top fade is the cover ground there too",
        )
        assertFalse("cosmoShell != null && !isDetailVisible" in text, "the detail-page exception is gone")
    }

    @Test fun theFadeBandUnderTheCapsulesIsOnlyDrawnWithTheLookOffAndTheTapGuardStays() {
        val text = main
        val guarded = Regex("""if \(cosmoShell == null\) BottomFadeScrim\(\s*withMiniPlayer = player\.song != null,""")
        assertEquals(1, guarded.findAll(text).count(), "the bottom fade is under `if (cosmoShell == null)`")
        assertEquals(2, Regex("""\bBottomFadeScrim\(""").findAll(mainCode).count(), "the top fade and the bottom fade are the only two calls")
        assertEquals(1, Regex("""if \(cosmoShell == null\) BottomFadeScrim\(""").findAll(text).count(), "only the second one is guarded: the top fade is the page's cover ground")
        assertTrue("FloatingBarsTapGuard(\n                    barsHeight = { floatingBarsHeight.intValue }," in text, "the tap guard stays, it reads the chrome's height")
    }

    @Test fun theNewChromeHasThreeTabsHomeSearchLibraryAndExploreHasNone() {
        val chrome = main.substringAfter("// The new look's floating chrome (tab capsule and mini capsule).").substringBefore("selectedIndex = ")
        assertEquals(3, Regex("""\bCosmoTab\(""").findAll(chrome).count(), "three tabs")
        assertTrue(
            "CosmoTab(homeLabel, CosmoIcons.Home, CosmoIcons.HomeFill), CosmoTab(searchLabel, CosmoIcons.Search, CosmoIcons.SearchFill), " +
                "CosmoTab(libraryLabel, CosmoIcons.Library, CosmoIcons.LibraryFill)" in squash(chrome),
            "Home, Search, Library, in that order",
        )
        assertFalse("exploreLabel" in chrome, "no Explore tab in the capsule")
        assertEquals(1, Regex("""\bCosmoTab\(homeLabel""").findAll(mainCode).count(), "and no second capsule definition elsewhere")
        assertTrue("// Home, Search (Explore lives inside it), Library" in main, "the comment names the order")
        assertFalse("// the order of TAB_HOME, TAB_EXPLORE, TAB_LIBRARY, TAB_SEARCH" in main, "the old comment is gone")
    }

    @Test fun theCapsuleMapsTheFourRootTabsOntoThreeAndBackAgain() {
        val text = main
        assertTrue(
            "selectedIndex = when (selectedTab) { TAB_HOME -> 0; TAB_SEARCH, TAB_EXPLORE -> 1; else -> 2 }," in text,
            "Explore (a mood page) keeps Search lit, Library is the rest",
        )
        assertTrue(
            "onTabSelected = { index -> onTabSelected(listOf(TAB_HOME, TAB_SEARCH, TAB_LIBRARY)[index]) }," in text,
            "the capsule's index 0, 1, 2 is Home, Search, Library; the one tab handler is the root's",
        )
        assertFalse("selectedIndex = selectedTab, onTabSelected = onTabSelected," in text, "the four-tab wiring is gone")
    }

    @Test fun theTabConstantsAreUntouched() {
        val text = main
        for (line in listOf("private const val TAB_HOME = 0", "private const val TAB_EXPLORE = 1", "private const val TAB_LIBRARY = 2", "private const val TAB_SEARCH = 3")) {
            assertTrue(line in text, "$line stays (decision E4)")
        }
    }

    @Test fun exploreWithoutAMoodPageHasNoTabSoTheNewLookShowsSearchAndOnlyTheNewLook() {
        // P4-20: this was a SideEffect that moved the saved tab to Search once Explore had composed (one frame of Explore). The tab the
        // page reads is now derived where it is read (`ShownTab` over `TabRedirect.shown`), so no frame composes Explore first; the
        // derivation is pinned by PolishContractTest and its rule by TabRedirectTest.
        val text = main
        assertFalse("androidx.compose.runtime.SideEffect { selectedTab = TAB_SEARCH }" in text, "no correction after the frame")
        assertEquals(1, Regex(Regex.escape("com.cosmotune.design.TabRedirect.shown(")).findAll(text).count(), "one derivation")
        assertTrue("newLook = cosmoShell != null" in text, "only the new look")
        assertTrue(text.indexOf("val selectedMoodGenre by ") in 0 until text.indexOf("com.cosmotune.design.TabRedirect.shown("), "the mood state is read above it")
        assertTrue(text.indexOf("val onTabSelected: (Int) -> Unit = { index ->") > 0, "the one tab handler is still there")
    }

    // ---- the upstream bars and the log ---------------------------------------------------------------------------------------

    @Test fun theUpstreamBarsAreStillThereForTheLookOff() {
        val src = frosted
        assertTrue(Regex("""\nfun FrostedTopBar\(""").containsMatchIn(src) && Regex("""\nfun TopBarAccountButton\(""").containsMatchIn(src), "both old composables are declared, untouched")
        assertFalse(src.contains("com.cosmotune"), "no edit in the old bar's file")
        assertTrue("if (cosmoShell != null) com.cosmotune.ui.shell.CosmoBottomChrome(" in main && "if (glassActive) Column(" in main && "FloatingBottomBar(" in main, "part one's chrome and the old chain are still the two branches")
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
