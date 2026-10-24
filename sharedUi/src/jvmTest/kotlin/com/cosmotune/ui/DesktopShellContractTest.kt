package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (the desktop's files live in `desktopApp/`): the new
 * sidebar and the delegate that hands the old one's state to it, the three tabs of a narrow window, the profile drawer on the top
 * bar's avatar with its five entries, the side panel's glass card and the sites in `DesktopApp.kt` and `DesktopSidePanel.kt`
 * that choose them. With the new look off every site takes its old branch, so what is pinned here is the seam: the old body
 * after the delegate, the old call after the new one, the card only under the look.
 */
class DesktopShellContractTest {
    private val desktopDir = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/"
    private val cosmoDir = "desktopApp/src/main/kotlin/com/cosmotune/desktop/"

    private val app get() = SourceContract.read(desktopDir + "DesktopApp.kt")
    private val appCode get() = SourceContract.code(app)
    private val panelFile get() = SourceContract.read(desktopDir + "DesktopSidePanel.kt")
    private val sidebar get() = SourceContract.read(cosmoDir + "CosmoDesktopSidebar.kt")
    private val panel get() = SourceContract.read(cosmoDir + "CosmoDesktopSidePanel.kt")
    private val chrome get() = SourceContract.read(cosmoDir + "CosmoDesktopChrome.kt")

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** Whether the code of [text] (comments removed, white space collapsed) has [needle] (collapsed the same way). */
    private fun has(text: String, needle: String) = squash(SourceContract.code(text)).contains(squash(needle))

    private fun assertHas(text: String, needle: String, message: String = "") = assertTrue(has(text, needle), "`$needle` is in the code. $message")

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

    /** The call that starts with [head] (which ends in its opening bracket) in [text], brackets included. */
    private fun callAt(text: String, head: String): String {
        val at = text.indexOf(head)
        assertTrue(at >= 0, "$head is in the text")
        return balanced(text, at + head.length - 1)
    }

    /** The declaration of `data class [name]` (its constructor), white space collapsed, without a trailing comma. */
    private fun dataClass(text: String, name: String): String {
        val head = "data class $name("
        val at = text.indexOf(head)
        assertTrue(at >= 0, "$head is declared")
        val whole = balanced(text, at + head.length - 1)
        return squash("$name${whole.removeSuffix(")").trimEnd().removeSuffix(",")})").replace("( ", "(")
    }

    /** `.live(` may only be read inside the lambdas of the draw phase. */
    private fun assertLiveOnlyWhileDrawing(name: String, text: String) {
        val code = SourceContract.code(text)
        var from = 0
        while (true) {
            val at = code.indexOf(".live(", from)
            if (at < 0) break
            val before = code.substring(0, at)
            val lastDraw = listOf("drawBehind {", "drawWithContent {", "drawWithCache {", "onDrawBehind {", "onDrawWithContent {", "graphicsLayer {", "Canvas(", "layout {")
                .maxOf { before.lastIndexOf(it) }
            val lastFun = before.lastIndexOf("\nfun ").coerceAtLeast(before.lastIndexOf("\nprivate fun ")).coerceAtLeast(before.lastIndexOf("\ninternal fun "))
            // A function on a DrawScope is itself a draw-phase scope: it can only be called from one.
            val lastFunLine = code.substring(lastFun + 1, code.indexOf('\n', lastFun + 1))
            assertTrue(lastDraw > lastFun || "DrawScope." in lastFunLine, "$name reads a live colour outside a draw-phase lambda, near char $at")
            from = at + 1
        }
    }

    // ---- Step 2: the sidebar delegate ----------------------------------------------------------------------------------------

    @Test fun theSidebarDelegateIsTheFirstStatementOfTheOldSidebar() {
        val body = SourceContract.functionBody(app, "DesktopSidebar")
        val first = SourceContract.firstStatement(body)
        val head = "if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.desktop.CosmoDesktopSidebar("
        assertTrue(first.startsWith(head), "the first statement is the delegate: ${first.take(160)}")
        assertEquals(1, Regex("""\bfun DesktopSidebar\(""").findAll(app).count(), "one old sidebar")
    }

    @Test fun theDelegatePassesTheSidebarsWholeStateAndEveryDestination() {
        val first = SourceContract.firstStatement(SourceContract.functionBody(app, "DesktopSidebar"))
        for (pair in listOf(
            "settingsSelected = settingsOpen", "onOpenSettings = onOpenSettings", "query = query", "onQueryChange = onQueryChange",
            "onSearch = onSearch", "focusSearch = focusSearch", "onSearchFocused = onSearchFocused",
        )) assertTrue(pair in first, "passes `$pair`")
        for (name in listOf("accountPlaylists", "localPlaylists", "openedCollectionId", "onOpenAccountPlaylist", "onOpenLocalPlaylist")) {
            assertTrue(Regex("""\b$name\b""").containsMatchIn(first), "the playlists come from `$name`")
        }
        assertTrue("playlists = com.cosmotune.desktop.cosmoSidebarPlaylists(" in first, "the playlists: the account's, then the local ones, mapped by our function")
        for (destination in listOf("LISTEN_NOW", "SEARCH", "EXPLORE", "LIBRARY", "HISTORY", "DOWNLOADS", "LOCAL_MUSIC")) {
            assertTrue("DesktopDestination.$destination" in first, "the destination $destination is mapped")
        }
        for (destination in listOf("LISTEN_NOW", "SEARCH", "LIBRARY", "HISTORY", "DOWNLOADS", "LOCAL_MUSIC")) {
            assertTrue("onDestinationSelected(DesktopDestination.$destination)" in first, "a click on the $destination row selects it")
        }
        assertFalse("onDestinationSelected(DesktopDestination.EXPLORE)" in first, "Explore has no row: it only keeps Search lit")
        assertTrue(
            "destination == DesktopDestination.SEARCH || destination == DesktopDestination.EXPLORE" in first,
            "Search is selected for Explore too",
        )
        assertEquals(6, Regex("""com\.cosmotune\.desktop\.CosmoDesktopNavItem\(""").findAll(first).count(), "six rows: Home, Search, Library, History, Downloads, Local Music")
        for (icon in listOf("Home", "HomeFill", "Search", "SearchFill", "Library", "LibraryFill", "Clock", "Download")) {
            assertTrue(Regex("""com\.cosmotune\.ui\.components\.CosmoIcons\.${icon}\b""").containsMatchIn(first), "the icon $icon")
        }
        assertTrue("DesktopDownloadQueue.active" in first && "aside =" in first, "the Downloads row shows the queued count as its aside")
    }

    @Test fun theOldSidebarBodyFollowsTheDelegateUnchanged() {
        val body = SourceContract.functionBody(app, "DesktopSidebar")
        val rest = body.substringAfter(SourceContract.firstStatement(body))
        for (kept in listOf(
            "DesktopSearchField(", "desktopWindowGlass(DesktopChromeEdge.END, fade = 0.07f)", "DesktopSidebarItem(CosmotuneIcons.Home", "Your playlists will appear here",
            "DesktopSidebarItem(Icons.Rounded.Settings", "SIDEBAR_CAPTION_HEIGHT", "searchFocusRequester.requestFocus()",
        )) assertTrue(kept in rest, "the old body still has `$kept`")
        assertEquals(1, Regex("""\bfun DesktopSidebarItem\(""").findAll(app).count(), "the old row is still declared")
    }

    // ---- Step 1: the new sidebar ---------------------------------------------------------------------------------------------

    @Test fun theSidebarTakesTheBriefsParametersAndItsTwoDataClasses() {
        assertEquals(
            listOf(
                "nav: List<CosmoDesktopNavItem>", "playlists: List<CosmoDesktopPlaylistItem>", "settingsSelected: Boolean",
                "onOpenSettings: () -> Unit", "query: String", "onQueryChange: (String) -> Unit", "onSearch: () -> Unit",
                "focusSearch: Boolean", "onSearchFocused: () -> Unit",
            ),
            SourceContract.parameters(sidebar, "CosmoDesktopSidebar"),
        )
        assertEquals(
            "CosmoDesktopNavItem(val icon: ImageVector, val selectedIcon: ImageVector, val label: String, val selected: Boolean, val aside: String? = null, val onClick: () -> Unit)",
            dataClass(sidebar, "CosmoDesktopNavItem"),
        )
        assertEquals(
            "CosmoDesktopPlaylistItem(val title: String, val subtitle: String?, val imageUrl: String?, val selected: Boolean, val onClick: () -> Unit)",
            dataClass(sidebar, "CosmoDesktopPlaylistItem"),
        )
    }

    @Test fun thePlaylistMapperTakesTheOldSidebarsFiveInputsAndKeepsTheOldSelectionTests() {
        assertEquals(
            listOf(
                "accountPlaylists: List<ShelfItem>", "localPlaylists: List<DesktopPlaylist>", "openedCollectionId: String?",
                "onOpenAccountPlaylist: (ShelfItem) -> Unit", "onOpenLocalPlaylist: (DesktopPlaylist) -> Unit",
            ),
            SourceContract.parameters(sidebar, "cosmoSidebarPlaylists"),
        )
        val code = squash(SourceContract.code(sidebar))
        assertTrue("selected = openedCollectionId == playlist.browseId" in code, "an account playlist is selected as the old sidebar did")
        assertTrue("selected = openedCollectionId == playlist.id" in code, "a local playlist is selected as the old sidebar did")
        assertTrue("onClick = { onOpenAccountPlaylist(playlist) }" in code && "onClick = { onOpenLocalPlaylist(playlist) }" in code, "a click opens the playlist the old way")
    }

    @Test fun theSidebarIsTheBriefsWidthOnTheAmbientGroundWithNoGlass() {
        val code = SourceContract.code(sidebar)
        assertHas(sidebar, "Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp).fillMaxHeight()", "220 dp: the page padding and the player bar host depend on it")
        for (glass in listOf("cosmoGlass", "desktopWindowGlass", "desktopChromeGlass", "hazeEffect", "LocalDesktopHaze", "HazeState")) {
            assertFalse(glass in code, "the sidebar sits on the ambient ground: no `$glass`")
        }
        // P4-20: the player bar floats to the right of the sidebar, never under it, so the sidebar keeps only the bar's own bottom margin
        assertHas(sidebar, "bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp", "the bar's bottom margin, 14 dp")
        assertFalse("desktopBarFootprintDp" in code, "no band for a bar that is not under the sidebar")
        assertHas(sidebar, "start = 12.dp, end = 12.dp, top = if (inlineCaption) 4.dp else 16.dp", "padding 16 12, and 4 under the caption as the old one")
    }

    @Test fun theCaptionRuleAndTheFocusHandlingAreTheOldOnes() {
        assertHas(sidebar, "val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled")
        assertHas(sidebar, "DesktopTitleBarDragArea(")
        assertHas(sidebar, "DesktopWindowButtons()")
        assertHas(sidebar, "padding(start = if (DesktopPlatform.isMac) 16.dp else 10.dp)", "the old caption's inset")
        assertHas(sidebar, "LaunchedEffect(focusSearch) { if (focusSearch) { runCatching { focusRequester.requestFocus() }; onSearchFocused() } }", "the field takes the focus when Search is picked, then says so")
        assertHas(sidebar, "windowInfo.isWindowFocused", "the old restore of the focus when the window comes back")
        assertHas(sidebar, "yield()", "the activation of the window finishes before the focus returns")
        assertHas(sidebar, "onFocusChanged = { searchFocused = it }")
    }

    @Test fun theSearchFieldIs44dpWithTheKeyHintOutsideMacOs() {
        val call = squash(callAt(SourceContract.code(sidebar), "CosmoSearchField("))
        assertTrue("height = SEARCH_HEIGHT" in call, call)
        assertTrue("keyHint = if (DesktopPlatform.isMac) null else \"Ctrl K\"" in call, call)
        assertTrue("onSubmit = onSearch" in call && "query = query" in call && "onQueryChange = onQueryChange" in call, call)
        assertTrue("focusRequester = focusRequester" in call, call)
        assertTrue("placeholder = " in call && "DesktopStrings[" in call, call)
        assertHas(sidebar, "private val SEARCH_HEIGHT = 44.dp")
    }

    @Test fun theRowsHaveTheBoardsNumbers() {
        for (line in listOf(
            "private val NAV_HEIGHT = 44.dp", "private val NAV_RADIUS = 22.dp", "private val NAV_PADDING = 14.dp", "private val NAV_GAP = 12.dp",
            "private val NAV_ICON = 20.dp", "private val NAV_LIST_GAP = 2.dp", "private const val NAV_LABEL_SP = 14.5f", "private const val NAV_LABEL_WEIGHT = 650",
            "private const val ASIDE_SP = 12f", "private const val LIBRARY_SP = 15f", "private const val LIBRARY_WEIGHT = 700",
            "private val ITEM_PADDING_X = 10.dp", "private val ITEM_PADDING_Y = 6.dp", "private val ITEM_RADIUS = 16.dp", "private val ITEM_GAP = 12.dp",
            "private val COVER_SIZE = 40.dp", "private val COVER_RADIUS = 10.dp", "private const val ITEM_TITLE_SP = 14f", "private const val ITEM_TITLE_WEIGHT = 650",
            "private const val ITEM_SUBTITLE_SP = 12.5f", "private const val EMPTY_SP = 13f",
        )) assertHas(sidebar, line)
    }

    @Test fun theNavRowsAreTonalWhenSelectedAndTheLabelsAreTheBriefs() {
        val code = squash(SourceContract.code(sidebar))
        assertTrue("if (selected) item.selectedIcon else item.icon" in code, "the selected icon when selected")
        assertTrue("CoverField.ON_ACCENT_CONTAINER" in code || "palette.onAccentContainer" in code, "selected text is onAccentContainer")
        assertTrue("palette.textSecondary" in code, "the rest is textSecondary")
        assertTrue("CoverField.ACCENT_STRONG" in code || "palette.accentStrong" in code, "the aside is accentStrong")
        assertTrue("palette.textTertiary" in code, "the empty line is textTertiary")
        assertTrue("CoverField.ACCENT_CONTAINER" in code, "the tonal fill is the container's gradient")
        assertTrue("ControlMetrics.TONAL_START_MIX" in code, "the tonal fill starts nine per cent towards the accent, as the controls' fill does")
        assertTrue("selectable(" in code && "Role.Tab" in code, "a row is one selectable thing with a role")
        assertTrue("cosmoFocusRing(" in code, "a visible focus ring")
        assertTrue("CoverImage(" in code && "shadow = false" in code, "playlist covers have no shadow")
        assertTrue("CosmoIcons.Queue" in code, "a playlist without a picture shows the queue glyph on a tonal tile")
        assertTrue("CosmoIcons.Settings" in code, "Settings is a row of its own at the bottom")
        assertTrue("DesktopStrings[\"d_sidebar_no_playlists\", \"Your playlists will appear here\"]" in code, "the empty line")
        assertTrue("DesktopStrings[\"library\", \"Library\"]" in code, "the Library heading")
        assertTrue("DesktopStrings[\"settings\", \"Settings\"]" in code, "the Settings label")
        assertTrue("forText(" in code, "playlist names in Indian scripts get the taller line")
    }

    @Test fun theSidebarReadsLiveColoursOnlyWhileDrawingAndRemembersNoBrush() {
        assertLiveOnlyWhileDrawing("CosmoDesktopSidebar", sidebar)
        assertFalse(Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\()""").containsMatchIn(SourceContract.code(sidebar)), "no brush or live colour is made once and kept")
        assertTrue("LazyColumn(" in sidebar && "key = " in sidebar, "the playlists are a lazy list with keys")
    }

    // ---- Step 3: the compact tabs --------------------------------------------------------------------------------------------

    private fun bottomBarBlock(): String {
        val text = app
        val start = text.indexOf("bottomBar = { compact ->")
        assertTrue(start >= 0, "the bottomBar lambda")
        val call = text.indexOf("if (cosmoShell != null) com.cosmotune.ui.shell.CosmoBottomChrome(", start)
        val old = text.indexOf("DesktopBottomChrome(\n                        compact = compact,", call)
        assertTrue(call in start..old && old > call, "the new chrome, then the old call")
        return text.substring(call, old)
    }

    @Test fun aNarrowWindowHasThreeTabsHomeSearchLibraryAndExploreHasNone() {
        val block = bottomBarBlock()
        val tabs = block.substringBefore("selectedIndex = ")
        assertEquals(3, Regex("""com\.cosmotune\.ui\.shell\.CosmoTab\(""").findAll(tabs).count(), "three tabs")
        val flat = squash(tabs)
        val order = listOf("DesktopStrings[\"home\", \"Home\"]", "DesktopStrings[\"search\", \"Search\"]", "DesktopStrings[\"library\", \"Library\"]").map { flat.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "Home, Search, Library in that order: $order")
        assertFalse("\"explore\"" in tabs, "no Explore tab")
        for (icon in listOf("CosmoIcons.Home, com.cosmotune.ui.components.CosmoIcons.HomeFill", "CosmoIcons.Search, com.cosmotune.ui.components.CosmoIcons.SearchFill", "CosmoIcons.Library, com.cosmotune.ui.components.CosmoIcons.LibraryFill")) {
            assertTrue(icon in flat, "the phone's icons: $icon")
        }
        assertTrue("// Home, Search (Explore inside it), Library" in tabs, "the comment names the order")
    }

    @Test fun theTabsMapTheFourDestinationsOntoThreeAndBack() {
        val block = squash(bottomBarBlock())
        assertTrue(
            "selectedIndex = when (destination) { DesktopDestination.LISTEN_NOW -> 0 DesktopDestination.SEARCH, DesktopDestination.EXPLORE -> 1 DesktopDestination.LIBRARY -> 2 else -> -1 }," in block,
            "Explore keeps Search lit; any other page lights nothing",
        )
        assertTrue(
            "onTabSelected = { index -> selectDestination( listOf(DesktopDestination.LISTEN_NOW, DesktopDestination.SEARCH, DesktopDestination.LIBRARY)[index], ) }," in block,
            "index 0, 1, 2 is Home, Search, Library",
        )
        assertTrue("hazeState = LocalDesktopHaze.current" in block, "the capsules still blur the page behind them")
    }

    // ---- Step 4: the profile drawer ------------------------------------------------------------------------------------------

    @Test fun theTopBarTakesTwoMoreOptionalParametersAndTheAvatarIsTheProfileButton() {
        val names = SourceContract.names(SourceContract.parameters(chrome, "CosmoDesktopTopBar"))
        assertEquals(listOf("compact", "canGoBack", "onBack", "accountAvatar", "onOpenAccounts", "accountName", "entries"), names)
        val params = SourceContract.parameters(chrome, "CosmoDesktopTopBar")
        assertEquals("accountName: String? = null", params[5])
        assertEquals("entries: List<CosmoDrawerEntry> = emptyList()", params[6])
        val call = squash(callAt(SourceContract.code(chrome), "CosmoProfileButton("))
        assertTrue("avatarUrl = accountAvatar" in call, call)
        assertTrue("name = accountName?.takeIf { it.isNotBlank() } ?: DesktopStrings[\"accounts\", \"Accounts\"]" in call, call)
        // Y-3: while the mode is Video the drawer lists Video's entries; Music's `entries` is the else branch.
        assertTrue("subtitle = null" in call && "entries = if (video) com.cosmotune.video.ui.desktop.videoDesktopDrawerEntries() else entries" in call && "onAccount = onOpenAccounts" in call, call)
        assertFalse("fun Avatar(" in chrome, "the old avatar is gone from the new bar")
        assertHas(chrome, "AVATAR_SIZE: Dp = 36.dp", "the avatar stays 36 dp")
    }

    @Test fun theTopBarCallPassesTheAccountNameAndSixEntriesInOrder() {
        val text = app
        val callAt = text.indexOf("if (cosmoShell != null) com.cosmotune.desktop.CosmoDesktopTopBar(")
        assertTrue(callAt >= 0)
        val call = squash(balanced(text, text.indexOf('(', callAt + "if (cosmoShell != null) com.cosmotune.desktop".length)))
        // The selected YouTube profile's name and picture, then the login's (upstream #614; DesktopAccountDisplayTest).
        assertTrue("accountName = activeAccount.shownName(activeProfileId)," in call, call)
        assertTrue("activeAccount.shownAvatar(activeProfileId)," in call, call)
        assertTrue("entries = listOf(" in call, call)
        assertEquals(6, Regex("""com\.cosmotune\.ui\.chrome\.CosmoDrawerEntry\(""").findAll(call).count(), "six entries")
        val parts = call.substringAfter("entries = listOf(").split("com.cosmotune.ui.chrome.CosmoDrawerEntry(").drop(1)
        assertTrue("listeningToolsOpen = true" in parts.first(), "the first entry opens listening tools")
        val (replay, together, downloads, equaliser, settings) = parts.drop(1)
        assertTrue(replay.startsWith("com.cosmotune.ui.components.CosmoIcons.Replay, DesktopStrings[\"replay\", \"Replay\"],"), replay)
        assertTrue("overlays.replay = true" in replay, "Replay opens the page")
        assertTrue(replay.indexOf("overlays.settingsPage = null") in 0 until replay.indexOf("overlays.replay = true"), "and leaves Settings first, which outranks it: $replay")
        assertTrue(together.startsWith("com.cosmotune.ui.components.CosmoIcons.Together, DesktopStrings[\"listen_together\", \"Listen together\"],"), together)
        assertTrue("overlays.listenTogether = true" in together, together)
        assertTrue(downloads.startsWith("com.cosmotune.ui.components.CosmoIcons.Download, DesktopStrings[\"downloads\", \"Downloads\"],"), downloads)
        assertTrue("selectDestination(DesktopDestination.DOWNLOADS)" in downloads, downloads)
        assertTrue(equaliser.startsWith("com.cosmotune.ui.components.CosmoIcons.Equaliser, DesktopStrings[\"equalizer\", \"Equalizer\"],"), equaliser)
        assertTrue("overlays.settingsPage = DesktopSettingsPage.EQUALIZER" in equaliser, "the statement of the Settings row's onOpenEqualizer: $equaliser")
        assertTrue(settings.startsWith("com.cosmotune.ui.components.CosmoIcons.Settings, DesktopStrings[\"settings\", \"Settings\"],"), settings)
        assertTrue("openSettings()" in settings, settings)
        val old = text.indexOf("DesktopTopBar(\n                        compact = compact,", callAt)
        assertTrue(old > callAt, "the old bar follows as the else branch")
        assertEquals("else", text.substring(callAt, old).trimEnd().substringAfterLast(")").trim())
    }

    @Test fun thereIsOneCtrlKShortcutOnlyUnderTheLookAndOutsideMacOs() {
        val code = squash(appCode)
        assertTrue(
            "cosmoShell != null && !DesktopPlatform.isMac && event.isCtrlPressed && event.key == Key.K -> { searchFocusRequested = true true }" in code,
            "the key cap on the new field says Ctrl K, so Ctrl K focuses it; with the look off nothing is added",
        )
        assertTrue("import androidx.compose.ui.input.key.isCtrlPressed" in app, "the import")
        assertTrue("DesktopPlatform.isMac && event.isMetaPressed && event.key == Key.F -> { searchFocusRequested = true" in code, "the macOS shortcut is as it was")
    }

    // ---- Step 5: the side panel ----------------------------------------------------------------------------------------------

    @Test fun theColumnIsStillTheOldOneWithTheCardChosenByTheLookThroughTheModifierChain() {
        val text = panelFile
        val code = squash(SourceContract.code(text))
        assertTrue(
            "Row(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight().then(com.cosmotune.desktop.cosmoDesktopSidePanelCard())) {" in code,
            "the column's own row takes the card as one more modifier",
        )
        assertTrue(
            "if (com.cosmotune.ui.theme.LocalCosmoShell.current == null) Box(Modifier.width(1.dp).fillMaxHeight().background(DesktopDivider))" in code,
            "the 1 dp rule beside the page is the old look's: the card is the edge now",
        )
        assertTrue(
            "if (which == DesktopSidePanel.QUEUE && com.cosmotune.ui.theme.LocalCosmoShell.current == null) {" in code,
            "the queue's own sidebar glass is a second layer over the card's: the old look's",
        )
        for (kept in listOf("private val SIDE_PANEL_WIDTH = 360.dp", "private const val SLIDE_MS = 260", "expandHorizontally(", "shrinkHorizontally(", "onClick = onClose", "DesktopToolbarButton(onClick = onClose")) {
            assertTrue(kept in code, "the width, the slide and the close button stay: `$kept`")
        }
        // `com.cosmotune.music` is the file's own package since the rename (FORK.md); only the calls into our code count.
        assertEquals(3, Regex("""com\.cosmotune(?!\.music\b)""").findAll(SourceContract.code(text)).count(), "three lines choose by the look and no more")
        assertTrue("desktopChromeGlass" in text, "the old glass call is still in the file")
    }

    @Test fun theCardIsTheBoardsAndExistsOnlyUnderTheLook() {
        val code = SourceContract.code(panel)
        assertEquals(listOf("open: Boolean", "content: @Composable () -> Unit"), SourceContract.parameters(panel, "CosmoDesktopSidePanel"))
        val body = squash(SourceContract.functionBody(panel, "cosmoDesktopSidePanelCard"))
        assertTrue(body.startsWith("{ if (com.cosmotune.ui.theme.LocalCosmoShell.current == null) return Modifier") || body.startsWith("{ if (LocalCosmoShell.current == null) return Modifier"), "with the look off the modifier is the identity: $body")
        assertTrue("top = PANEL_MARGIN" in body && "end = PANEL_MARGIN" in body && "bottom = ShellMetrics.desktopBarFootprintDp().dp" in body, "margins 14 (top, end) and the player bar's footprint at the bottom: $body")
        assertTrue("SuperellipseShape(CosmoRadii.xl)" in body, "a superellipse of 28: $body")
        assertTrue(".cosmoGlass(LocalDesktopHaze.current, shape)" in body, "glass over the window's haze: $body")
        // P4-20: no padding of the card's own: the column's header (18 dp in) and content (18 dp for the lyrics, 14 dp for the queue)
        // carry the inset, so a card padding of 14 dp on top of them put the content 28 to 32 dp from the edge (the board: 14)
        assertFalse("PANEL_PADDING" in code, "the card has no padding of its own: the column's content has its own")
        assertTrue(".cosmoGlass(LocalDesktopHaze.current, shape)" in body && body.endsWith(".cosmoGlass(LocalDesktopHaze.current, shape) }"), "margin, then glass, and nothing inside it: $body")
        assertHas(panel, "private val PANEL_MARGIN = 14.dp")
        assertFalse("ShellMetrics.DESKTOP_SIDE_PANEL_DP" in code, "the width is the column's own")
    }

    @Test fun theHazeLocalIsVisibleToTheNewFilesAndNothingElseAboutItChanged() {
        val code = squash(appCode)
        assertTrue("internal val LocalDesktopHaze = staticCompositionLocalOf<HazeState?> { null }" in code, "visible to the panel's modifier, which lives in another file")
        assertFalse("private val LocalDesktopHaze" in code, "no longer private")
        assertEquals(1, Regex("""\bval LocalDesktopHaze\b""").findAll(appCode).count())
    }

    // ---- the log -------------------------------------------------------------------------------------------------------------

    @Test fun theForkLogHasARowForEachUpstreamFileWithFourColumnsAndEveryRowHasFour() {
        val lines = SourceContract.read("FORK.md").lines()
        val table = lines.filter { it.startsWith("|") }
        assertTrue(table.size > 90, "the table was read")
        for (row in table) assertEquals(5, row.count { it == '|' }, "four columns (no pipe inside a cell): ${row.take(120)}")
        val appRows = table.filter { it.startsWith("| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt`") && "CosmoDesktopSidebar" in it }
        assertEquals(1, appRows.size, "one new row for the sites of DesktopApp.kt")
        for (word in listOf("DesktopSidebar", "CosmoTab", "CosmoDrawerEntry", "LocalDesktopHaze", "isCtrlPressed", "accountName")) {
            assertTrue(word in appRows.single(), "the row names $word")
        }
        val panelRows = table.filter { it.startsWith("| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopSidePanel.kt`") }
        assertEquals(1, panelRows.size, "one row for DesktopSidePanel.kt")
        assertTrue("cosmoDesktopSidePanelCard" in panelRows.single() && "DesktopDivider" in panelRows.single(), "the row names the card and the divider")
    }
}
