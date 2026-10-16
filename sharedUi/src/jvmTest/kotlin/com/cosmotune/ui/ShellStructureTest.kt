package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no render can show, read from the source of the phone shell and of its wiring in `MainActivity`: the three edit
 * sites that make the new chrome and the new player replace the old ones, the old bars staying as the `else` branch, the
 * dismiss local declared once, and no colour or brush made once and remembered. The look itself is on the phone.
 */
class ShellStructureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val shellDir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell"
    private val mainActivityPath = "app/src/main/java/com/music/bitchord/MainActivity.kt"

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
        val reads = Regex("""position\.positionMs""").findAll(text).map { it.range.first }.toList()
        assertEquals(1, reads.size, "the playhead is read in exactly one place")
        val at = reads.single()
        val capsuleStart = text.indexOf("private fun MiniCapsule(")
        assertTrue(capsuleStart in 0 until at, "and it is in the mini capsule")
        assertTrue(text.lastIndexOf(".drawBehind {", at) > capsuleStart, "inside its drawBehind block, so a tick of the song recomposes nothing")
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
        assertTrue("selectableGroup()" in chrome && "Role.Tab" in chrome, "the bar is a group and each tab has the tab role")
        assertTrue("Haptic.Select" in chrome, "a tab plays the select haptic")
    }

    @Test fun theCapsuleReportsWhereItIsAndGoesInvisibleWhileThePlayerStandsInItsPlace() {
        assertTrue("capsuleBounds = it.boundsInRoot()" in chrome, "bounds are reported in root coordinates")
        assertTrue("drawWithContent { if (shell?.capsuleHidden != true) drawContent() }" in chrome, "the capsule is not drawn while hidden, decided in the draw phase like the overlay's card")
        assertTrue("Haptic.Expand" in chrome && "Res.string.now_playing" in chrome, "expanding plays its haptic and the capsule is named")
    }

    // ---- MainActivity: the three sites ----

    @Test fun dismissPlayerDoesNotAskTheOldSheetMotionWhenTheNewShellIsOn() {
        val text = mainActivity
        assertTrue(
            "if (reducePlayerMotion || cosmoShell != null) showNowPlaying = false else playerSheetMotion.close()" in text,
            "PlayerSheetMotion.close() latches `closing` when it finds no sheet; the new shell has none",
        )
    }

    @Test fun theNewChromeWrapsTheUpstreamBarsAsTheirElseBranch() {
        val text = mainActivity
        val chromeCall = text.indexOf("if (cosmoShell != null) com.cosmotune.ui.shell.CosmoBottomChrome(")
        assertTrue(chromeCall >= 0, "the chrome is called under `if (cosmoShell != null)`")
        val glass = text.indexOf("if (glassActive) Column(", chromeCall)
        assertTrue(glass > chromeCall, "the upstream chain follows the chrome")
        assertEquals("else", text.substring(chromeCall, glass).trimEnd().substringAfterLast(")").trim(), "directly after the chrome call comes `else`, then the upstream chain")
        assertTrue("} else Column(" in text.substring(glass), "the upstream chain keeps its own else (the old floating bar and mini player)")
        assertTrue("GlassNavBar(" in text.substring(glass) && "MiniPlayer(" in text.substring(glass) && "FloatingBottomBar(" in text.substring(glass), "the three upstream bars are still there")
    }

    @Test fun theChromeReportsItsHeightToTheTapGuard() {
        val text = mainActivity
        assertTrue("hazeState = hazeState, onHeightChanged = { floatingBarsHeight.intValue = it }" in text, "the guard reads the chrome's height")
    }

    @Test fun theOverlayReplacesTheOldSheetWhenTheNewShellIsOn() {
        val text = mainActivity
        assertTrue("if (cosmoShell != null) playerSong?.let { song ->" in text && "com.cosmotune.ui.shell.CosmoPlayerOverlay(open = showNowPlaying" in text, "the overlay is mounted for the song that is playing")
        assertTrue("if (playerRaised && cosmoShell == null) {" in text, "the old sheet is only raised with the switch off")
        val overlayAt = text.indexOf("com.cosmotune.ui.shell.CosmoPlayerOverlay(")
        val oldSheetAt = text.indexOf("// ---- Now Playing ----")
        assertTrue(overlayAt in 0 until oldSheetAt, "the overlay comes before the old Now Playing block")
    }
}
