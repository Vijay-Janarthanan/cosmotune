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
}
