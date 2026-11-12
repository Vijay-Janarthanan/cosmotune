package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no render can show, read from the source of the phone shell and of its wiring in `MainActivity`: the three edit
 * sites where the chrome and the player stand in for the old ones (which are gone, with no branch left to them), the
 * dismiss local declared once, and no colour or brush made once and remembered. The look itself is on the phone.
 */
class ShellStructureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val shellDir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell"
    private val mainActivityPath = "app/src/main/java/com/cosmotune/music/MainActivity.kt"

    private fun read(path: String): String {
        val file = File(root, path)
        if (!file.isFile) fail("$path is not where this test expects it")
        return file.readText().replace("\r\n", "\n")
    }

    private val chrome get() = read("$shellDir/CosmoBottomChrome.kt")
    private val overlay get() = read("$shellDir/CosmoPlayerOverlay.kt")
    private val mainActivity get() = read(mainActivityPath)

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

    @Test fun theChromeTakesTheParametersOfTheBriefInOrder() {
        assertEquals(
            listOf(
                "tabs", "selectedIndex", "onTabSelected", "song", "isPlaying", "isLoading", "position", "durationMs", "onPlayPause", "onNext",
                "onExpand", "controlsLocked", "onBlockedControl", "hazeState", "onHeightChanged", "modifier", "notices",
                // Video mode (Y-1): the mode this chrome belongs to, last, so every caller that passes its arguments by name is untouched.
                "mode",
                // The dock (revamp): a swipe to the right plays the previous song, and Video's own mini player stands inside the dock.
                // Last again, with defaults, so every caller that passes its arguments by name is untouched.
                "onPrevious", "miniPlayer", "onClose", "showTabs", "dark", "ownMedia",
            ),
            parameterNames(signature(chrome, "CosmoBottomChrome")),
        )
    }

    @Test fun theChromeStepsAsideForAnotherModeAsItsFirstStatement() {
        assertTrue("mode: AppMode = AppMode.MUSIC," in signature(chrome, "CosmoBottomChrome").replace(Regex("\\s+"), " "), "the mode defaults to Music, so Music's callers are untouched")
        val body = SourceContract.functionBody(chrome, "CosmoBottomChrome")
        assertEquals(
            "if ((LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC) != mode) return",
            SourceContract.firstStatement(body),
            "a chrome that belongs to another mode draws nothing",
        )
    }

    @Test fun theOverlayTakesTheParametersOfTheBriefInOrder() {
        assertEquals(
            listOf("open", "onDismiss", "shell", "modifier", "content"),
            parameterNames(signature(overlay, "CosmoPlayerOverlay")),
        )
    }

    @Test fun theDismissLocalIsDeclaredOnceAndTheOverlayProvidesIt() {
        val declarations = File(root, "sharedUi/src").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""val\s+LocalCosmoPlayerDismiss\b""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("PlayerDismiss.kt"), declarations, "LocalCosmoPlayerDismiss is declared in PlayerDismiss.kt and nowhere else")
        assertTrue(Regex("""LocalCosmoPlayerDismiss\s+provides""").containsMatchIn(overlay), "the overlay provides the local around the player's content")
    }

    @Test fun noBrushOrLiveColourIsRememberedAtCreation() {
        // The designer's round-1 bug: a brush made once at theme creation makes every control the fallback apricot.
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\()""")
        for ((name, text) in listOf("CosmoBottomChrome" to chrome, "CosmoPlayerOverlay" to overlay)) {
            val bad = remembering.find(text)
            assertTrue(bad == null, "$name remembers a brush or a live colour: ${bad?.value}")
        }
    }

    @Test fun liveColoursAreReadOnlyInDrawPhaseLambdas() {
        val drawLambdas = listOf("drawBehind {", "drawWithContent {", "graphicsLayer {")
        for ((name, text) in listOf("CosmoBottomChrome" to chrome, "CosmoPlayerOverlay" to overlay)) {
            var from = 0
            while (true) {
                val at = text.indexOf(".live(", from)
                if (at < 0) break
                val line = text.lineSequence().take(text.substring(0, at).count { it == '\n' } + 1).last()
                val before = text.substring(0, at)
                // The nearest opening of a lambda that is a draw phase must be after the last function boundary above the call.
                val lastDraw = drawLambdas.maxOf { before.lastIndexOf(it) }
                val lastFun = before.lastIndexOf("\nfun ").coerceAtLeast(before.lastIndexOf("\nprivate fun ")).coerceAtLeast(before.lastIndexOf("\ninternal fun "))
                assertTrue(lastDraw > lastFun, "$name reads a live colour outside a draw-phase lambda: $line")
                from = at + 1
            }
        }
    }

    @Test fun theMiniCapsuleReadsThePlayheadOnlyWhenDrawing() {
        val text = chrome
        assertTrue(!Regex("""position\.positionMs""").containsMatchIn(text), "the stepped reading is not read here at all")
        val reads = Regex("""playhead\.fraction\(""").findAll(text).map { it.range.first }.toList()
        assertEquals(1, reads.size, "the interpolated playhead is read in exactly one place")
        val at = reads.single()
        val capsuleStart = text.indexOf("private fun MiniCapsule(")
        assertTrue(capsuleStart in 0 until at, "and it is in the mini capsule")
        assertTrue(text.lastIndexOf("progress = {", at) > capsuleStart, "inside the orbit's progress lambda, read while the orbit draws, so a tick of the song recomposes nothing")
    }

    @Test fun theOverlayKeepsItsLayerUntilTheCapsuleIsBackAndStopsTheSpringAtItsEnds() {
        val text = overlay
        assertTrue("derivedStateOf { open || progress.value > 0f || shell.capsuleHidden }" in text, "no frame is left with neither the card nor the capsule")
        assertTrue("remember(open) { derivedStateOf" in text, "the visibility reads the current `open`, not the one of the first composition")
        assertTrue("progress.updateBounds(0f, 1f)" in text, "the move spring overshoots; the progress ends on the capsule and on the window")
        assertTrue("CoroutineStart.UNDISPATCHED" in text, "a step of a drag is queued before the release that follows it")
    }

    @Test fun theMorphReadsTheProgressInTheDrawPhaseOnly() {
        val text = overlay
        val morph = text.substring(text.indexOf("private fun MorphLayer("))
        assertTrue("graphicsLayer" !in morph, "a layer block is applied after the draw lambdas of its frame: the cover would lag the card")
        assertTrue("drawWithContent" in morph && "color = {" in morph, "the cover is drawn translated and scaled, and the text takes its fade as a colour producer")
    }

    @Test fun theTabBarIsASelectableGroupOfTabs() {
        assertTrue("selectableGroup()" in chrome && "role = Role.Tab" in read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitDock.kt"), "the bar is a group and each tab (OrbitDockTab) has the tab role")
        assertTrue("Haptic.Select" in chrome, "a tab plays the select haptic")
    }

    @Test fun theCapsuleReportsWhereItIsAndGoesInvisibleWhileThePlayerStandsInItsPlace() {
        assertTrue("capsuleBounds = it.boundsInRoot()" in chrome, "bounds are reported in root coordinates")
        assertTrue("drawWithContent { if (ownMedia || shell?.capsuleHidden != true) drawContent() }" in chrome, "the capsule is not drawn while hidden, decided in the draw phase like the overlay's card")
        assertTrue("Haptic.Expand" in chrome && "Res.string.now_playing" in chrome, "expanding plays its haptic and the capsule is named")
    }

    // ---- The dock (revamp) ----

    @Test fun theSmallPlayerIsAColourCardOverTheTabBarAndNothingIsGlass() {
        val code = SourceContract.code(chrome)
        assertEquals(0, Regex("""\.cosmoGlass\(""").findAll(code).count(), "Orbit rule 4: the dock is flat, no glass")
        val body = SourceContract.functionBody(chrome, "CosmoBottomChrome")
        assertTrue(body.indexOf("MiniCapsule(") in 0 until body.indexOf("FloatingTabBar("), "the small player sits over the tabs")
        assertTrue("miniPlayer()" in body, "Video's own mini player stands in the same place")
        assertTrue("Spacer(Modifier.height(DOCK_GAP))" in body, "the board's two pieces, 8 dp apart")
        val mini = SourceContract.functionBody(chrome, "MiniCapsule")
        assertTrue("coverFill(coverUrl, song.videoId)" in mini && ".clip(OrbitMiniMetrics.shape)" in mini, "the desktop bar's colour card with the bite at its end")
        assertTrue("OrbitPlayButton(isPlaying, onPlayPause, look, size = OrbitMiniMetrics.PLAY_DP.dp, touch = OrbitMiniMetrics.TOUCH_DP.dp)" in mini, "play stands in the bite, with a 48 dp touch target")
    }

    @Test fun theChosenTabIsASolidChipWithItsNameAndEveryTabIsNamed() {
        val dock = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitDock.kt")
        val tab = SourceContract.functionBody(dock, "OrbitDockTab")
        assertTrue("if (selected) {" in tab && "Text(label" in tab, "the chosen tab shows its name")
        assertTrue("Modifier.semantics { contentDescription = label }" in tab, "the others are named for a screen reader")
        assertTrue("role = Role.Tab" in tab, "each is a tab")
        val bar = SourceContract.functionBody(chrome, "FloatingTabBar")
        assertTrue("OrbitDockMetrics.BAR_DP" in bar && "orbitDockFill(look)" in bar, "the board's 60 dp bar on a solid ground")
    }

    @Test fun aSidewaysSwipeSkipsAndAnUpwardOneOpensThePlayer() {
        val mini = SourceContract.functionBody(chrome, "MiniCapsule")
        assertTrue("orientation = Orientation.Vertical" in mini && "orientation = Orientation.Horizontal" in mini)
        assertTrue("Haptic.SkipNext" in mini && "Haptic.SkipPrevious" in mini)
        assertTrue("if (controlsLocked) onBlockedControl()" in mini, "a locked party answers the swipe with the host-only notice")
        assertTrue("CustomAccessibilityAction(previous)" in mini, "previous is reachable without the gesture")
        assertTrue("if (!reduced) translationX" in mini, "the row does not follow the finger with reduced motion")
    }

    @Test fun theProgressIsTheOrbitRoundTheDisc() {
        val mini = SourceContract.functionBody(chrome, "MiniCapsule")
        assertTrue("OrbitRing(" in mini && "playhead.fraction(durationMs)" in mini, "the orbit is the progress (Orbit rule 2)")
        assertTrue("enabled = onPrevious != null" in mini, "the sideways swipe is opt-in")
    }

    @Test fun theDockPublishesItsHeightForThePagesUnderIt() {
        val body = SourceContract.functionBody(chrome, "CosmoBottomChrome")
        assertTrue("shell?.dockHeightPx = it.height" in body, "the measured height, gap to the edge included")
        assertTrue("onDispose { shell.dockHeightPx = 0 }" in body, "and nothing once the dock is gone")
        assertTrue("fun rememberDockClearance(" in chrome)
    }

    // ---- MainActivity: the three sites ----

    @Test fun dismissPlayerOnlyClosesThePlayer() {
        val text = mainActivity
        assertTrue("val dismissPlayer: () -> Unit = { showNowPlaying = false }" in text, "the overlay animates itself out; there is no sheet motion to ask")
        assertTrue(!File(root, "app/src/main/java/com/cosmotune/music/ui/player/PlayerSheetMotion.kt").exists(), "and so is its file")
    }

    @Test fun theChromeIsTheOnlyBottomBar() {
        val text = mainActivity
        assertEquals(1, Regex(Regex.escape("com.cosmotune.ui.shell.CosmoBottomChrome(")).findAll(text).count(), "the chrome is called once")
        val chromeCall = text.indexOf("com.cosmotune.ui.shell.CosmoBottomChrome(")
        val before = text.substring(0, chromeCall).trimEnd().lines().last().trim()
        assertTrue(!before.startsWith("if") && !before.startsWith("else"), "the chrome is drawn unconditionally, not behind a test: $before")
        val code = SourceContract.code(text)
        for (old in listOf("glassActive", "GlassNavBar(", "MiniPlayer(", "FloatingBottomBar(")) assertTrue(old !in code, "$old is gone from the phone root")
        for (file in listOf("GlassNavBar.kt", "MiniPlayer.kt", "FloatingBottomBar.kt", "TabDrag.kt", "floatingtabbar")) {
        }
    }

    @Test fun theChromeReportsItsHeightToTheTapGuard() {
        val text = mainActivity
        assertTrue("hazeState = hazeState, onHeightChanged = { floatingBarsHeight.intValue = it }" in text, "the guard reads the chrome's height")
    }

    @Test fun theOverlayIsThePlayer() {
        val text = mainActivity
        assertTrue(
            "playerSong?.let { song ->\n            com.cosmotune.ui.shell.CosmoPlayerOverlay(open = showNowPlaying, onDismiss = { showNowPlaying = false }, shell = cosmoShell)" in text,
            "the overlay is mounted, unconditionally, for the song that is playing",
        )
    }
}
