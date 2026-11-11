package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no render can show, read from the source of the desktop chrome and of its wiring in `DesktopApp.kt` and
 * `DesktopPlayerSheet.kt`: the five edit sites that put the new bars, the new player and the new padding in place of the old
 * ones with the old code as the `else` (so that with the switch off every line takes its old branch), the player bar's
 * signature, and the rule that no colour is made once and kept and that the playhead is read in three small places.
 */
class DesktopChromeStructureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val desktopDir = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop"

    private fun read(path: String): String {
        val file = File(root, path)
        if (!file.isFile) fail("$path is not where this test expects it")
        return file.readText().replace("\r\n", "\n")
    }

    private val bar get() = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoPlayerBar.kt")
    private val chrome get() = read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt")
    private val app get() = read("$desktopDir/DesktopApp.kt")
    private val sheet get() = read("$desktopDir/DesktopPlayerSheet.kt")

    private fun signature(text: String, function: String): String {
        val start = text.indexOf("fun $function(")
        if (start < 0) fail("no `fun $function(` found")
        var depth = 0
        for (i in start until text.length) {
            if (text[i] == '(') depth++
            if (text[i] == ')' && --depth == 0) return text.substring(start, i + 1)
        }
        fail("unbalanced signature of $function")
    }

    /** The names of the top-level parameters: what sits inside a function type's own parentheses is not one of them. */
    private fun parameterNames(signature: String): List<String> {
        val flat = StringBuilder()
        var depth = 0
        for (c in signature.substringAfter("(")) {
            if (c == '(') depth++
            if (depth == 0) flat.append(c)
            if (c == ')') depth--
        }
        return Regex("""(?:^|
|,)\s*([a-zA-Z]+)\s*:""").findAll(flat).map { it.groupValues[1] }.toList()
    }

    // ---- The player bar ----

    @Test fun thePlayerBarTakesTheParametersOfTheBriefInOrderThenOnlyOptionalOnes() {
        assertEquals(
            listOf(
                "song", "isPlaying", "isLoading", "position", "durationMs", "liked", "shuffle", "repeatMode", "controlsLocked", "lyricsOn",
                "queueOn", "onPlayPause", "onPrevious", "onNext", "onToggleShuffle", "onCycleRepeat", "onToggleLike", "onSeekFraction",
                "onLyrics", "onQueue", "onOutput", "onExpand", "onBlockedControl", "hazeState", "modifier", "volumeLabel", "embedded",
            ),
            parameterNames(signature(bar, "CosmoPlayerBar")),
        )
        assertTrue("volumeLabel: String? = null" in bar && "embedded: Boolean = false" in bar, "the two parameters beyond the brief have defaults, so a call written to the brief still compiles")
    }

    @Test fun thePlayerBarReportsWhereItIsAndGoesInvisibleWhileThePlayerStandsInItsPlace() {
        val text = bar
        assertTrue("capsuleBounds = it.boundsInRoot()" in text, "bounds are reported in root coordinates, for the player to grow from")
        assertTrue("drawWithContent { if (capsuleShell?.capsuleHidden != true) drawContent() }" in text, "not drawn while hidden, decided in the draw phase like the overlay's card")
        assertTrue("capsuleShell.capsuleLabel = label" in text && "capsuleShell.capsuleBounds = null; capsuleShell.capsuleLabel = null" in text, "what the morph copies is published and withdrawn with the bar")
        // The bar inside the open player is not the capsule: it has no shell to report to, so it never moves the capsule's bounds or hides itself.
        assertTrue("val capsuleShell = if (embedded) null else shell" in text, "an embedded bar reports nothing and is never hidden")
    }

    @Test fun thePlayerBarIsAnOrbitColourCardWithPlayInTheBiteAtItsEnd() {
        val text = bar
        assertTrue("coverFill(coverUrl, song.videoId)" in text && ".clip(OrbitBarMetrics.shape).background(fill)" in text, "a flat card in the cover's deep colour, cut by the end bite")
        assertTrue("cosmoGlass" !in text && "SuperellipseShape" !in text, "no glass: Orbit cards are flat")
        assertTrue(".height(OrbitBarMetrics.HEIGHT_DP.dp)" in text, "the shell's 80 dp")
        assertTrue("Disc(coverUrl, OrbitBarMetrics.DISC_DP.dp)" in text && "OrbitRing(OrbitBarMetrics.RING_DP.dp" in text, "the cover is a disc in its orbit")
        assertTrue("OrbitPlayButton(isPlaying, onPlayPause, look, size = OrbitBarMetrics.PLAY_DP.dp)" in text && "Alignment.CenterEnd" in text, "play stands in the bite at the end")
        assertTrue("OrbitProgressLine(" in text && "scrub.release(onSeekFraction)" in text, "the kit's seek line, holding where it was dropped")
    }

    @Test fun everyControlInTheBarIsAKitPiece() {
        val text = bar
        assertFalse(Regex("""(CosmoIconButton|PlayMorphButton)\(""").containsMatchIn(text), "no old-look buttons in the Orbit bar")
        assertTrue(Regex("""OrbitRoundButton\(""").findAll(text).count() >= 8, "shuffle, previous, next, repeat, like, lyrics, queue and output are the kit's round buttons")
    }

    @Test fun noBrushOrLiveColourIsRememberedAtCreation() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\()""")
        for ((name, text) in listOf("CosmoPlayerBar" to bar, "CosmoDesktopChrome" to chrome)) {
            val bad = remembering.find(text)
            assertTrue(bad == null, "$name remembers a brush or a live colour: ${bad?.value}")
        }
    }

    @Test fun liveColoursAreReadOnlyInDrawPhaseLambdas() {
        val text = bar
        var from = 0
        while (true) {
            val at = text.indexOf(".live(", from)
            if (at < 0) break
            val before = text.substring(0, at)
            val lastDraw = listOf("drawBehind {", "drawWithContent {", "graphicsLayer {").maxOf { before.lastIndexOf(it) }
            val lastFun = before.lastIndexOf("\nfun ").coerceAtLeast(before.lastIndexOf("\nprivate fun ")).coerceAtLeast(before.lastIndexOf("\ninternal fun "))
            assertTrue(lastDraw > lastFun, "the player bar reads a live colour outside a draw-phase lambda, near char $at")
            from = at + 1
        }
    }

    @Test fun thePlayheadIsReadByTheClockTheOrbitAndTheSeekLineOnly() {
        val text = bar
        val reads = Regex("""position\.positionMs""").findAll(text).map { it.range.first }.toList()
        assertEquals(1, reads.size, "only the clock reads the stepped reading: no other place reads the playhead")
        val clock = text.indexOf("private fun Clock(")
        assertTrue(reads.all { it > clock && it < text.indexOf("private fun VolumeBar(") }, "it is in Clock, which recomposes alone")
        // The orbit's and the seek line's progress lambdas read the playhead run on every frame, while drawing.
        val live = Regex("""playhead\.positionMs\(\)""").findAll(text).map { it.range.first }.toList()
        assertEquals(2, live.size, "the orbit's progress lambda and the seek line's")
        assertEquals(2, live.count { text.lastIndexOf("progress = {", it) > text.lastIndexOf("\n", it) }, "both are progress lambdas, read when drawing")
    }

    @Test fun theNarrowPlanKeepsLyricsAndQueueAndTheVolumeIsTheHostsOwn() {
        val text = bar
        assertTrue("if (plan.volume)" in text && "PlayerPlatform.host.volume" in text && "volume::set" in text, "the volume bar drives the host's system volume")
        assertTrue("if (plan.output)" in text, "the output button is the narrow plans' first loss after the volume")
        assertTrue(!Regex("""if \(plan\.\w*\) \{?\s*OrbitRoundButton\(\s*CosmoIcons\.(Lyrics|Queue)""").containsMatchIn(text), "lyrics and queue are in every plan")
    }

    // ---- The desktop chrome ----

    @Test fun theTopBarIsTheBriefs() {
        val text = chrome
        // The five of part one, the two the profile drawer needs (P4-13) and the search button of a compact window, all with defaults.
        assertEquals(listOf("compact", "canGoBack", "onBack", "accountAvatar", "onOpenAccounts", "accountName", "entries", "onSearch"), parameterNames(signature(text, "CosmoDesktopTopBar")))
        assertTrue("accountName: String? = null" in text && "entries: List<CosmoDrawerEntry> = emptyList()" in text && "onSearch: (() -> Unit)? = null" in text, "the new parameters have defaults, so a call of the first five still compiles")
        assertTrue("DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT))" in text && "TOP_BAR_HEIGHT: Dp = 56.dp" in text, "a 56 dp drag area")
        assertTrue("compact && DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled" in text, "the old bar's rule for the window buttons in a compact window")
        assertTrue("painterResource(Res.drawable.logo_mark)" in text && "Modifier.size(width = 28.dp, height = 18.dp)" in text, "the mark at 28 x 18 dp")
        assertTrue("colorFilter = if (com.cosmotune.ui.theme.LocalCosmoColors.current.dark) null" in text, "untinted (apricot) on the dark theme, the brand's ink on the light one")
        assertTrue("CosmoIcons.ChevronLeft" in text && "CosmoIconButtonStyle.TONAL" in text && "enabled = backEnabled" in text, "a tonal back button")
        // Y-3: the button is enabled by `backEnabled`, which is canGoBack in Music and Video's own back in Video.
        assertTrue("val backEnabled = if (video) com.cosmotune.video.ui.desktop.VideoDesktopBack.canGoBack else canGoBack" in text, "Music's rule is the else branch")
        assertTrue("AVATAR_SIZE: Dp = 36.dp" in text, "a 36 dp avatar")
    }

    @Test fun theHostPlacesTheBarClearOfTheSidebarAndTheColumn() {
        val text = chrome
        assertEquals(listOf("panelOpen", "content"), parameterNames(signature(text, "CosmoDesktopPlayerBarHost")))
        assertTrue("maxWidth < ShellMetrics.DESKTOP_COMPACT_DP.dp" in text, "below the compact width it draws nothing: the phone's chrome is the bottom bar")
        assertTrue("(ShellMetrics.DESKTOP_SIDEBAR_DP + SIDE_MARGIN).dp" in text, "start: the sidebar and a margin")
        assertTrue("(ShellMetrics.DESKTOP_SIDE_PANEL_DP + SIDE_MARGIN).dp" in text, "end, while the column is open: the column and a margin")
        assertTrue("bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp" in text && "Alignment.BottomStart" in text, "14 dp above the bottom")
    }

    // ---- DesktopApp.kt and DesktopPlayerSheet.kt: the edit sites ----

    @Test fun theTopBarIsTheNewOneUnderTheSwitchAndTheOldOneIsTheElseBranch() {
        val text = app
        val call = text.indexOf("if (cosmoShell != null) com.cosmotune.desktop.CosmoDesktopTopBar(")
        assertTrue(call >= 0, "the new top bar is called under `if (cosmoShell != null)`")
        val old = text.indexOf("DesktopTopBar(\n                        compact = compact,", call)
        assertTrue(old > call, "the old call follows with its own arguments")
        assertEquals("else", text.substring(call, old).trimEnd().substringAfterLast(")").trim(), "directly after the new call comes `else`, then the old call")
        assertTrue("navHistory.isNotEmpty()" in text.substring(call, old) && "::goBack" in text.substring(call, old), "back is the app's one way back")
    }

    @Test fun theBottomBarIsThePhonesChromeUnderTheSwitchAndTheOldOneIsTheElseBranch() {
        val text = app
        val call = text.indexOf("if (cosmoShell != null) com.cosmotune.ui.shell.CosmoBottomChrome(")
        assertTrue(call >= 0, "the shared chrome is called under `if (cosmoShell != null)`")
        val old = text.indexOf("DesktopBottomChrome(\n                        compact = compact,", call)
        assertTrue(old > call, "the old call follows with its own arguments")
        val between = text.substring(call, old)
        assertEquals("else", between.trimEnd().substringAfterLast(")").trim(), "directly after the new call comes `else`, then the old call")
        // DESIGN_SYSTEM.md 5.2: Home, Explore, Releases and Library; Search is the top bar's button.
        for (key in listOf("\"home\"", "SharedRes.string.explore", "SharedRes.string.cosmo_releases", "\"library\"")) assertTrue(key in between, "the tab label $key")
        assertTrue("\"search\"" !in between, "no Search tab")
        assertTrue("selectedIndex = MUSIC_DOCK.indexOf(destination)," in between, "the selected tab; any other page lights nothing")
        assertTrue("listOf(DesktopDestination.LISTEN_NOW, DesktopDestination.EXPLORE, DesktopDestination.RELEASES, DesktopDestination.LIBRARY)" in text, "the dock's order")
        assertTrue("onTabSelected" in between && "selectDestination(" in between, "a tab selects its destination")
        assertTrue("hazeState = LocalDesktopHaze.current" in between, "the capsules blur the page behind them")
    }

    @Test fun thePlayerBarIsMountedBeforeTheSheetUnderTheSwitch() {
        val text = app
        val host = text.indexOf("if (cosmoShell != null) com.cosmotune.desktop.CosmoDesktopPlayerBarHost(overlays.sidePanel != null) { m ->")
        val sheet = text.indexOf("DesktopPlayerSheet(\n                        visible = overlays.nowPlaying && selectedSong != null,")
        assertTrue(host in 0 until sheet, "the bar is drawn first, so the player that grows out of it is drawn over it")
        val block = text.substring(host, sheet)
        for (needle in listOf("modifier = m", "lyricsOn = overlays.sidePanel == DesktopSidePanel.LYRICS", "queueOn = overlays.sidePanel == DesktopSidePanel.QUEUE", "overlays.toggleSidePanel(DesktopSidePanel.LYRICS)", "overlays.toggleSidePanel(DesktopSidePanel.QUEUE)", "overlays.audioOutput = true", "overlays.nowPlaying = true", "hazeState = LocalDesktopHaze.current")) {
            assertTrue(needle in block, "the bar's call has `$needle`")
        }
        assertTrue("selectedSong?.let { current ->" in block, "it draws nothing while no song is selected")
    }

    @Test fun thePagePaddingHasALineForTheNewShellAndKeepsTheOldTwo() {
        val text = app
        // P4-20: exactly what floats over the page (the wide bar's footprint, the compact window's phone chrome), no longer 168 dp for both
        val at = Regex("""bottom = when \{\s*com\.cosmotune\.ui\.theme\.LocalCosmoShell\.current != null ->""").find(text)?.range?.first ?: -1
        assertTrue(at >= 0, "the padding under the page while the new shell is on is the first branch of a `when`")
        val rest = text.substring(at, at + 500).replace(Regex("\\s+"), " ")
        assertTrue(
            "com.cosmotune.design.ShellMetrics.desktopPageBottomDp(compact, songShown = LocalNowPlaying.current != null).dp" in rest,
            "the bar's footprint in a wide window with a song, the phone chrome's height in a compact one, nothing for a wide one without (P4-22)",
        )
        assertTrue("compact -> 128.dp" in rest && "else -> 16.dp" in rest, "the old two follow, so with the switch off nothing changes")
    }

    @Test fun theSheetHandsTheNewLookToTheSharedOverlayAndKeepsItsOwnBodyForTheOldOne() {
        val text = sheet
        val first = text.indexOf("com.cosmotune.ui.theme.LocalCosmoShell.current?.let { shell ->\n        com.cosmotune.ui.shell.CosmoPlayerOverlay(visible, onDismiss, shell) { w, h ->")
        assertTrue(first >= 0, "the first statement of the sheet hands over to the overlay")
        assertTrue("LocalPlayerVolumeLabel provides DesktopStrings[\"d_volume\", \"Volume\"]) { content(w, h) }" in text, "the player's own bar names its volume bar in the desktop's words")
        assertTrue(text.indexOf("return\n    }", first) in first until text.indexOf("var mounted by remember"), "and the sheet returns before its old body")
        val body = text.indexOf("var mounted by remember { mutableStateOf(visible) }")
        assertTrue(body > first, "the old body follows")
        assertEquals(1, Regex("""fun DesktopPlayerSheet\(""").findAll(text).count())
        assertTrue(text.substring(text.indexOf("fun DesktopPlayerSheet("), first).count { it == '\n' } < 10, "it is the first statement of the function")
    }
}
