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
                "com.cosmotune.ui.theme.LocalCosmoShell.current != null -> (if (compact) com.cosmotune.design.ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = true) " +
                    "else com.cosmotune.design.ShellMetrics.desktopBarFootprintDp()).dp compact -> 128.dp else -> 16.dp",
            ),
            "the wide page pads 108 dp for the bar, the compact one 154 dp for the capsules; the old two follow",
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
}
