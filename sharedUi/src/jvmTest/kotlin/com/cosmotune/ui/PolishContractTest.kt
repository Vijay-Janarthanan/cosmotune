package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behaviour fixes of the polish task (P4-20), pinned by reading the source: what cannot be seen without a window. One section
 * per item; each pins the literal text of the upstream site it changed (so a merge that undoes the fix shows up here) and the
 * look-off rule (with the look off the old line runs as before).
 */
class PolishContractTest {
    private val app = "app/src/main/java/com/music/bitchord/"
    private val desktop = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/"
    private val cosmoDesktop = "desktopApp/src/main/kotlin/com/cosmotune/desktop/"

    // ---- 1. the equaliser toggle ticks once -----------------------------------------------------------------------------------

    @Test fun theEqualizerScreensOwnToggleHapticPlaysOnlyWithTheLookOff() {
        val src = SourceContract.code(SourceContract.read("${app}ui/screens/EqualizerScreen.kt"))
        val flat = src.replace(Regex("\\s+"), " ")
        // read in composition (a callback cannot read a composition local), then the switch's callback asks it: CosmoSwitch
        // (through the bridge) already ticks with the look on
        assertTrue(
            flat.contains("val cosmoLookOff = com.cosmotune.ui.theme.LocalCosmoShell.current == null"),
            "the screen reads whether the look is off in composition",
        )
        assertTrue(
            flat.contains("onCheckedChange = { if (cosmoLookOff) haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)"),
            "the switch's callback plays its haptic only with the look off",
        )
        assertFalse(
            Regex("""onCheckedChange = \{\s*haptics\.play""").containsMatchIn(src),
            "no unconditional haptic in the switch's callback",
        )
        // the row's own tap (not the switch) still ticks once in both looks
        assertTrue(
            Regex("""onClick = \{\s*haptics\.play\(if \(!enabled\) Haptic\.ToggleOn else Haptic\.ToggleOff\)""").containsMatchIn(src),
            "the row's tap keeps its haptic",
        )
    }

    // ---- 2. the playing bars of the desktop rows move ---------------------------------------------------------------------------

    @Test fun theDesktopRowsAndPagesReadWhetherAudioIsPlayingFromOneLocalOfOurs() {
        val row = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSongRow.kt"))
        assertTrue(
            Regex("""internal val LocalCosmoPlaying = compositionLocalOf \{ false \}""").containsMatchIn(row),
            "the local is declared in our file, false by default",
        )
        for ((file, function) in listOf(
            "CosmoDesktopSongRow.kt" to "CosmoDesktopSongRow",
            "CosmoDesktopCollectionPage.kt" to "CosmoDesktopCollectionPage",
            "CosmoDesktopArtistPage.kt" to "CosmoDesktopArtistPage",
        )) {
            val parameters = SourceContract.parameters(SourceContract.read("$cosmoDesktop$file"), function)
            assertTrue(
                "isPlaying: Boolean = LocalCosmoPlaying.current" in parameters,
                "$function's isPlaying starts from the local, not from a constant false: $parameters",
            )
        }
    }

    @Test fun theDesktopRootProvidesThePlayersFlagNextToTheNowPlayingSong() {
        val src = SourceContract.code(SourceContract.read("${desktop}DesktopApp.kt")).replace(Regex("\\s+"), " ")
        assertTrue(
            src.contains("LocalNowPlaying provides selectedSong, com.cosmotune.desktop.LocalCosmoPlaying provides playback.isPlaying,"),
            "the root provides playback.isPlaying beside the playing song",
        )
        // nobody sets it to a constant behind the root's back
        assertFalse(Regex("""LocalCosmoPlaying provides (true|false)""").containsMatchIn(src), "no constant is provided")
    }

    @Test fun theDesktopHomeHearsWhichSongIsPlayingAsTheDesktopSearchDoes() {
        val src = SourceContract.code(SourceContract.read("${desktop}DesktopApp.kt"))
        // (the old Home would draw its own indicator from the same two values, and never had them on the desktop: only the new look gets them)
        for ((page, song, playing) in listOf(
            Triple("com.cosmotune.ui.home.HomeSwitch", "currentSong = playback.song.takeIf { cosmoShell != null }", "isPlaying = cosmoShell != null && playback.isPlaying"),
            Triple("com.cosmotune.ui.search.SearchSwitch", "currentSong = playback.song", "isPlaying = playback.isPlaying"),
        )) {
            val at = src.indexOf("$page(")
            assertTrue(at >= 0, "$page is called")
            val open = at + page.length
            var depth = 0
            var end = open
            while (end < src.length) {
                when (src[end]) { '(' -> depth++; ')' -> if (--depth == 0) break }
                end++
            }
            val call = src.substring(open, end)
            assertTrue(call.contains(song), "$page is told the engine's live song")
            assertTrue(call.contains(playing), "$page is told whether the audio plays: its cards' bars move")
        }
    }

    // ---- 3. the frame's ambient ground under a window material --------------------------------------------------------------------

    private val frame: String
        get() = SourceContract.functionBody(SourceContract.read("${desktop}DesktopApp.kt"), "DesktopFrame").replace(Regex("\\s+"), " ")

    @Test fun theNewLooksFrameDrawsTheAmbientGroundBelowTheTitleBarWhateverTheMaterial() {
        val text = frame
        assertTrue(
            text.contains("val cosmoGround = com.cosmotune.ui.theme.LocalCosmoShell.current != null"),
            "the frame asks the look once, in composition",
        )
        val ground = "if (glass && cosmoGround) Box(Modifier.fillMaxSize().hazeSource(haze)) { backdrop(false) }"
        assertEquals(1, Regex(Regex.escape(ground)).findAll(text).count(), "the ground of the look is drawn once with a material, as a source for the bar's blur")
        assertTrue(
            text.indexOf("DesktopTitleBar()") < text.indexOf(ground) && text.indexOf(ground) < text.indexOf("BoxWithConstraints("),
            "it lies under the sidebar, the top bar, the page and the side column, and below the title bar (which keeps the material)",
        )
    }

    @Test fun theFlatSlabsAndThePagesOwnBackdropAreOnlyForTheOldLook() {
        val text = frame
        assertFalse(text.contains("if (glass && !materialBehindApp) {"), "no flat container colour behind the page or the column of the new look")
        assertEquals(
            2,
            Regex(Regex.escape("if (glass && !materialBehindApp && !cosmoGround) { Modifier.background(containerColor) } else { Modifier }")).findAll(text).count(),
            "the page's and the column's flat colour only without the new look",
        )
        assertTrue(text.contains("if (glass && !cosmoGround) backdrop(materialBehindApp)"), "the page's own backdrop is the old look's: the ground is already behind it")
        assertFalse(Regex("""if \(glass\) backdrop\(materialBehindApp\)""").containsMatchIn(text), "no unconditional page backdrop is left")
    }

    @Test fun withTheLookOffTheFrameIsTheOldOne() {
        val text = frame
        assertTrue(text.contains("if (!glass) Box(Modifier.fillMaxSize().hazeSource(haze)) { backdrop(false) }"), "the old window ground is untouched")
        assertTrue(text.contains(".then(if (glass) Modifier else Modifier.background(containerColor))"), "the old window colour is untouched")
        assertTrue(text.contains("val materialBehindApp = glass && appBackground"), "the old material rule is untouched")
    }

    // ---- 4. the bottom bands ---------------------------------------------------------------------------------------------------

    @Test fun thePagePadsForExactlyTheFloatingChrome() {
        val text = frame
        assertTrue(
            text.contains(
                "com.cosmotune.ui.theme.LocalCosmoShell.current != null -> " +
                    "com.cosmotune.design.ShellMetrics.desktopPageBottomDp(compact, songShown = LocalNowPlaying.current != null).dp compact -> 128.dp else -> 16.dp",
            ),
            "the wide page pads 108 dp for the bar while a song is selected, the compact one 154 dp (80 without a song) for the capsules; the old two follow",
        )
        assertFalse(text.contains("168.dp"), "no slack: 168 dp was the phone's chrome plus 14")
    }

    @Test fun theSidebarKeepsNoBandForABarThatFloatsBesideIt() {
        val src = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSidebar.kt")).replace(Regex("\\s+"), " ")
        assertTrue(src.contains("bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp"), "the sidebar's last row sits at the bar's own margin")
        assertFalse(src.contains("desktopBarFootprintDp"), "the 108 dp band (the board's, where the bar runs under the sidebar) is gone")
    }

    @Test fun theSidePanelsCardAddsNoPaddingOnTopOfTheColumnsOwn() {
        val src = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSidePanel.kt"))
        assertFalse(src.contains("PANEL_PADDING"), "no 14 dp inside the card: the header and content of the column carry 14 to 18 dp themselves")
        val body = SourceContract.functionBody(SourceContract.read("${cosmoDesktop}CosmoDesktopSidePanel.kt"), "cosmoDesktopSidePanelCard").replace(Regex("\\s+"), " ")
        assertTrue(body.trimEnd().endsWith(".cosmoGlass(LocalDesktopHaze.current, shape) }"), "the card is margin and glass, nothing inside: $body")
    }

    // ---- 5. the error page keeps its Tune icon -------------------------------------------------------------------------------------

    @Test fun theDesktopErrorPageHandsItsTuneIconToTheNewState() {
        val src = SourceContract.read("${desktop}DesktopApp.kt")
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, "DesktopErrorPage"))
        assertTrue("icon = Icons.Rounded.Tune" in statement, "the new state draws the old page's icon, not the default play triangle: $statement")
        // the old branch's icon is the same one, so the two looks agree
        assertTrue(
            SourceContract.code(SourceContract.functionBody(src, "DesktopErrorPage")).contains("DesktopEmptyPage(Icons.Rounded.Tune, "),
            "the old page still draws Tune",
        )
    }

    // ---- 6. holding the liked tile opens its menu -------------------------------------------------------------------------------

    private val shared = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/"

    @Test fun theMorphBoxCanTakeAHoldAndKeepsItsPlainClickWithout() {
        val src = SourceContract.read("${shared}components/MorphBox.kt")
        assertTrue("onLongPress: (() -> Unit)? = null" in SourceContract.parameters(src, "MorphBox"), "an optional hold on the box")
        val code = SourceContract.code(src).replace(Regex("\\s+"), " ")
        assertTrue(
            code.contains("onLongPress == null -> Modifier.clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)"),
            "without a hold the box is the plain clickable it was",
        )
        assertTrue(
            code.contains("else -> Modifier.combinedClickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick, onLongClick = onLongPress)"),
            "with a hold it is a combined clickable on the same interaction source, so the press morph and the state layer still follow it",
        )
    }

    @Test fun theLikedTileTakesAnOptionalHoldAndTheLibraryWiresItToTheRowsMenu() {
        val tiles = SourceContract.read("${shared}blocks/CosmoTiles.kt")
        assertEquals(
            listOf("title: String", "subtitle: String?", "onClick: () -> Unit", "modifier: Modifier = Modifier", "onLongPress: (() -> Unit)? = null"),
            SourceContract.parameters(tiles, "CosmoLikedTile"),
        )
        val body = SourceContract.code(SourceContract.functionBody(tiles, "CosmoLikedTile")).replace(Regex("\\s+"), " ")
        assertTrue(body.contains("onLongPress = onLongPress"), "the hold reaches the box")
        assertTrue(body.contains(".contextClick(onLongPress)"), "a right click on the desktop is the hold, as on the mix tile")
        val library = SourceContract.code(SourceContract.read("${shared}library/CosmoLibrary.kt")).replace(Regex("\\s+"), " ")
        assertTrue(
            library.contains("CosmoLikedTile( title = liked.title, subtitle = localizeCardSubtitle(liked.subtitle).takeIf { it.isNotBlank() }, onClick = { onClick(liked) }, modifier = Modifier.weight(1f), onLongPress = { onLongPress(liked) }, )"),
            "holding the liked tile calls the same hold as every other playlist: the menu the old row opened",
        )
    }

    // ---- 7. the artist page's play button toggles while the artist is playing -----------------------------------------------------

    @Test fun thePhoneArtistPageTakesAnOptionalPlayPauseAndDecidesWithTheSharedRule() {
        val page = SourceContract.read("${shared}pages/CosmoArtistPage.kt")
        assertEquals("onPlayPause: (() -> Unit)? = null", SourceContract.parameters(page, "CosmoArtistPage").last(), "the last parameter is the optional toggle")
        val code = SourceContract.code(page).replace(Regex("\\s+"), " ")
        assertTrue(code.contains("val artistPlaying = ArtistPlay.showsPause(songs, currentSong, isPlaying)"), "the button reads as pause by the shared rule")
        assertTrue(code.contains("ArtistPlay.tap(songs, currentSong, canToggle = onPlayPause != null)"), "the tap asks the shared rule")
        assertTrue(code.contains("ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()") && code.contains("ArtistPlay.Tap.PLAY_LIST -> onSongClick(songs, 0)"), "a toggle toggles, otherwise the list plays from the top")
        assertTrue(code.contains("onPlayPause = onPlayPause"), "the page hands the toggle down to the list")
    }

    @Test fun theDetailSwitchPassesTheRootsToggleToTheArtistPageOnlyAndKeepsTheOldCallAsItWas() {
        val switch = SourceContract.read("app/src/main/java/com/cosmotune/ui/pages/DetailSwitch.kt")
        val old = SourceContract.parameters(SourceContract.read("${app}ui/screens/DetailScreen.kt"), "DetailScreen")
        val own = SourceContract.parameters(switch, "DetailSwitch")
        assertEquals(old, own.take(old.size), "the switch starts with the old screen's parameters, as written")
        assertEquals(listOf("onPlayPause: (() -> Unit)? = null"), own.drop(old.size), "and has one more, defaulted, at the end")
        val body = SourceContract.code(SourceContract.functionBody(switch, "DetailSwitch")).replace(Regex("\\s+"), " ")
        val artist = body.substringAfter("CosmoArtistPage(").substringBefore("CosmoReleasePage(")
        assertTrue(artist.contains("onPlayPause = onPlayPause"), "the artist page gets the toggle")
        assertEquals(1, Regex("""onPlayPause = onPlayPause""").findAll(body).count(), "and only the artist page")
        assertFalse(body.substringAfter("} else { DetailScreen(").contains("onPlayPause"), "the old screen's call does not know it")
    }

    @Test fun theRootHandsThePhonesToggleToTheDetailSwitch() {
        val src = SourceContract.code(SourceContract.read("${app}MainActivity.kt"))
        val at = src.indexOf("com.cosmotune.ui.pages.DetailSwitch(")
        assertTrue(at >= 0, "the call is there")
        val open = at + "com.cosmotune.ui.pages.DetailSwitch".length
        var depth = 0
        var end = open
        while (end < src.length) {
            when (src[end]) { '(' -> depth++; ')' -> if (--depth == 0) break }
            end++
        }
        val call = src.substring(open, end).replace(Regex("\\s+"), " ")
        assertTrue(call.contains("onPlayPause = togglePlayPause"), "the call hands over the root's own toggle (the one the mini player uses)")
    }

    @Test fun theDesktopArtistPageReadsTheRootsToggleFromOneLocalOfOursAndDecidesWithTheSharedRule() {
        val page = SourceContract.read("${cosmoDesktop}CosmoDesktopArtistPage.kt")
        val row = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSongRow.kt"))
        assertTrue(Regex("""internal val LocalCosmoPlayPause = compositionLocalOf<\(\(\) -> Unit\)\?> \{ null \}""").containsMatchIn(row), "the local is ours, null where nobody provides it")
        assertEquals("onPlayPause: (() -> Unit)? = LocalCosmoPlayPause.current", SourceContract.parameters(page, "CosmoDesktopArtistPage").last())
        val code = SourceContract.code(page).replace(Regex("\\s+"), " ")
        assertTrue(code.contains("ArtistPlay.showsPause(top, nowPlaying, isPlaying)"), "the button reads as pause by the shared rule")
        assertTrue(code.contains("ArtistPlay.tap(top, nowPlaying, canToggle = onPlayPause != null)"), "the tap asks the shared rule")
        assertTrue(code.contains("ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()") && code.contains("ArtistPlay.Tap.PLAY_LIST -> if (top.isNotEmpty()) onPlaySongs(top, 0)"), "a toggle toggles, otherwise the list plays from the top")
        assertFalse(code.contains("playing = false"), "the button is no longer stuck on play")
    }

    @Test fun theDesktopRootProvidesItsStableToggleBesideThePlayersFlag() {
        val src = SourceContract.code(SourceContract.read("${desktop}DesktopApp.kt")).replace(Regex("\\s+"), " ")
        assertTrue(
            src.contains("com.cosmotune.desktop.LocalCosmoPlaying provides playback.isPlaying, com.cosmotune.desktop.LocalCosmoPlayPause provides com.cosmotune.desktop.rememberStableAction(::togglePlayPauseFromUser),"),
            "the root provides the toggle that honours Listen Together's lock, with one identity",
        )
        val row = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSongRow.kt")).replace(Regex("\\s+"), " ")
        assertTrue(row.contains("internal fun rememberStableAction(action: () -> Unit): () -> Unit"), "the helper is in our file")
        assertTrue(row.contains("val latest by rememberUpdatedState(action) return remember { { latest() } }"), "one identity, always the latest action")
    }

    // ---- 9. Explore without a mood page is Search before it composes ------------------------------------------------------------

    @Test fun theShownTabIsDerivedWhereItIsReadNotCorrectedAfterwards() {
        val src = SourceContract.code(SourceContract.read("${app}MainActivity.kt"))
        val flat = src.replace(Regex("\\s+"), " ")
        assertFalse(flat.contains("androidx.compose.runtime.SideEffect { selectedTab = TAB_SEARCH }"), "no correction after the frame is composed")
        assertTrue(flat.contains("val selectedTabState = rememberSaveable { mutableIntStateOf(0) }"), "the saved tab is held under its own name")
        assertFalse(flat.contains("var selectedTab by rememberSaveable"), "and is not the variable the page reads")
        val derived = "var selectedTab by com.cosmotune.ui.chrome.ShownTab(selectedTabState) { stored -> com.cosmotune.design.TabRedirect.shown( stored, newLook = cosmoShell != null, moodPageOpen = selectedMoodGenre != null, exploreTab = TAB_EXPLORE, searchTab = TAB_SEARCH, ) }"
        val at = flat.replace("( ", "(").replace(", )", ")").indexOf(derived.replace("( ", "(").replace(", )", ")"))
        assertTrue(at >= 0, "the tab the page reads is derived from the saved one")
        assertTrue(flat.indexOf("val selectedMoodGenre by ") in 0 until flat.indexOf("var selectedTab by com.cosmotune.ui.chrome.ShownTab("), "after the mood state it reads")
        assertTrue(flat.indexOf("var selectedTab by com.cosmotune.ui.chrome.ShownTab(") < flat.indexOf("val currentListState = when (selectedTab)"), "and before the first read")
        // no read or write of the name before it is declared (a local cannot be used above its declaration, so the build says so too)
        assertEquals(0, Regex("""\bselectedTab\b""").findAll(flat.substring(0, flat.indexOf("var selectedTab by com.cosmotune.ui.chrome.ShownTab("))).count(), "nothing uses the name above it")
    }

    @Test fun theShownTabDelegateReadsThroughTheRuleAndWritesTheSavedTab() {
        val src = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/ui/chrome/ShownTab.kt")).replace(Regex("\\s+"), " ")
        assertTrue(src.contains("class ShownTab(private val stored: MutableIntState, private val shown: (Int) -> Int)"), "a delegate over the saved state")
        assertTrue(src.contains("operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = shown(stored.intValue)"), "a read gives the shown tab")
        assertTrue(src.contains("operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { stored.intValue = value }"), "a write is the saved tab")
    }

    // ---- 8. the page actions of the phone's top bar are glass circles in the new look ----------------------------------------------

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ").trim()

    @Test fun theTopBarGivesItsActionsSlotTheGlassStyleAndNothingElse() {
        val bar = flat(SourceContract.code(SourceContract.read("${shared}chrome/CosmoTopBar.kt")))
        assertTrue(
            bar.contains("CompositionLocalProvider(LocalCosmoActionStyle provides CosmoActionStyle.GLASS) { actions() }"),
            "the slot's content (and nothing else of the bar) is composed under the glass style",
        )
        val style = flat(SourceContract.code(SourceContract.read("${shared}chrome/CosmoActionStyle.kt")))
        assertTrue(style.contains("enum class CosmoActionStyle { GLASS }"), "one style")
        assertTrue(style.contains("val LocalCosmoActionStyle = compositionLocalOf<CosmoActionStyle?> { null }"), "null (no style) where nobody provides it")
        // the switch only chooses between the two bars: the wrap is the new bar's own
        assertFalse(SourceContract.read("app/src/main/java/com/cosmotune/ui/chrome/TopBarSwitch.kt").contains("LocalCosmoActionStyle"), "the switch does not know the style")
    }

    @Test fun theBridgedIconButtonIsMaterialsOwnOutsideTheGlassStyleAndTheGlassButtonInsideIt() {
        val src = SourceContract.read("${shared}bridge/IconButtonBridge.kt")
        assertEquals(
            listOf(
                "onClick: () -> Unit", "modifier: Modifier = Modifier", "enabled: Boolean = true", "colors: IconButtonColors = IconButtonDefaults.iconButtonColors()",
                "interactionSource: MutableInteractionSource? = null", "content: @Composable () -> Unit",
            ),
            SourceContract.parameters(src, "IconButton"),
            "Material's own parameter list (material3 1.3.x), so an upstream file takes it by changing one import line",
        )
        val body = flat(SourceContract.code(SourceContract.functionBody(src, "IconButton")))
        assertTrue(
            body.startsWith("{ if (LocalCosmoShell.current == null || LocalCosmoActionStyle.current != CosmoActionStyle.GLASS) { androidx.compose.material3.IconButton( onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, interactionSource = interactionSource, content = content, ) return }"),
            "the first statement hands every parameter, by name, to Material's own button unless the look is on and the style is glass: $body",
        )
        assertTrue(body.contains("CosmoGlassActionButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)"), "inside the style it is the glass circle")
    }

    @Test fun theGlassActionButtonIsTheGlassIconButtonWithASlot() {
        val src = SourceContract.read("${shared}components/CosmoGlassActionButton.kt")
        assertEquals(
            listOf("onClick: () -> Unit", "modifier: Modifier = Modifier", "enabled: Boolean = true", "content: @Composable () -> Unit"),
            SourceContract.parameters(src, "CosmoGlassActionButton"),
        )
        val body = flat(SourceContract.code(SourceContract.functionBody(src, "CosmoGlassActionButton")))
        assertTrue(body.contains(".minimumInteractiveComponentSize() .size(40.dp)"), "a 40 dp circle that reserves a 48 dp touch target")
        assertTrue(body.contains("ControlMorph.iconButton(40f, false, pressed, true)"), "the morph of the glass icon button at 40 dp")
        assertTrue(body.contains("floating = true") && body.contains("role = Role.Button"), "a floating button, a Button")
        assertTrue(body.contains("fill = { c.glassFill() }") || body.contains("fill = { colors.glassFill() }"), "the glass fill")
        assertTrue(body.contains("glassEdge(outline, "), "and the glass edge")
        assertTrue(body.contains("LocalContentColor provides Color(palette.textPrimary)"), "an icon with no tint of its own is drawn in ink")
        assertTrue(body.contains("Box(Modifier.size(ControlMetrics.iconButtonIconDp(40f).dp), contentAlignment = Alignment.Center) { content() }"), "held to the glass icon button's 20 dp icon")
        assertFalse(body.contains("live("), "no live colour is read in composition")
    }

    @Test fun theTwoUpstreamFilesThatDrawTheActionsTakeIconButtonFromTheBridge() {
        for (file in listOf("${app}MainActivity.kt", "${app}ui/components/DownloadManagerSheet.kt")) {
            val src = SourceContract.read(file)
            assertTrue(src.contains("\nimport com.cosmotune.ui.bridge.IconButton\n"), "$file imports the bridge's IconButton")
            assertFalse(src.contains("\nimport androidx.compose.material3.IconButton\n"), "$file no longer imports Material's")
            assertEquals(1, Regex("""(?m)^import .*\.IconButton$""").findAll(src).count(), "$file imports IconButton once")
        }
    }
}
