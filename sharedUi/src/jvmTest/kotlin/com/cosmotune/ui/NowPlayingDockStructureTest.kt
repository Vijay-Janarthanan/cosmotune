package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no render can show of NP-1 (the controls of Music's Now Playing that can be minimized over a video), read from the source:
 * the progress and the playhead are read in layout, layer, draw and derived-state lambdas only; nothing blurs or samples the
 * video; the handle and the bar describe themselves to screen readers; the numbers of the owner's brief; and the seam in the
 * page, which keeps the stack's call and the video exactly as they were.
 */
class NowPlayingDockStructureTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying"
    private val sheetSrc = SourceContract.read("$dir/NowPlayingControlsSheet.kt")
    private val dockSrc = SourceContract.read("$dir/NowPlayingDock.kt")
    private val stateSrc = SourceContract.read("$dir/ControlsSheetState.kt")
    private val sheet = SourceContract.code(sheetSrc)
    private val dock = SourceContract.code(dockSrc)
    private val state = SourceContract.code(stateSrc)
    private val screen = SourceContract.code(SourceContract.read("$dir/CosmoNowPlaying.kt"))
    private val media = SourceContract.code(SourceContract.read("$dir/NowPlayingMedia.kt"))

    /** The openers of the lambdas a per-frame value may be read in. */
    private val frameSafe = listOf("graphicsLayer {", ".layout {", "layout {", "drawBehind {", "derivedStateOf {", "drawWithContent {")

    /** True when the read at [at] lies inside one of the [frameSafe] lambdas: the last opener before it is not yet closed. */
    private fun insideFrameSafeLambda(src: String, at: Int): Boolean {
        val opener = frameSafe.maxOf { src.lastIndexOf(it, at) }
        if (opener < 0) return false
        var depth = 0
        for (i in opener until at) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth > 0
    }

    private fun assertReadsOnlyInFrameSafeLambdas(src: String, name: String, read: String) {
        val hits = Regex(Regex.escape(read)).findAll(src).map { it.range.first }.toList()
        for (at in hits) assertTrue(insideFrameSafeLambda(src, at), "$name reads $read in composition at offset $at")
    }

    // ---- per frame --------------------------------------------------------------------------------------------------------

    @Test fun theProgressAndTheRevealAreReadOnlyInLayoutLayerAndDrawLambdas() {
        assertTrue(sheet.contains("sheet.progress.value"), "the sheet follows the progress")
        assertReadsOnlyInFrameSafeLambdas(sheet, "NowPlayingControlsSheet.kt", "progress.value")
        assertReadsOnlyInFrameSafeLambdas(sheet, "NowPlayingControlsSheet.kt", "reveal.value")
        assertReadsOnlyInFrameSafeLambdas(sheet, "NowPlayingControlsSheet.kt", "hidden.value")
        assertFalse(dock.contains("progress.value"), "the bar does not read the progress at all: its caller's layer does")
    }

    @Test fun theStateHolderReadsTheProgressInDerivedStatesAndSuspendFunctionsOnly() {
        val composable = state.substringAfter("internal fun rememberControlsSheet(")
        assertFalse(composable.contains("progress.value"), "the composable that remembers the sheet never reads the progress")
        for (line in state.lines().filter { it.contains("progress.value") && it.startsWith("    val ") }) {
            assertTrue(line.contains("derivedStateOf"), "a property over the progress is a derived state: $line")
        }
        assertTrue(state.contains("updateBounds(ControlsSheetRules.EXPANDED, ControlsSheetRules.MINIMIZED)"), "bounded: the spring ends on its target")
    }

    @Test fun theBarReadsThePlayheadOnlyWhileDrawingAndInOneDerivedState() {
        val reads = Regex("""position\.positionMs""").findAll(dock).map { it.range.first }.toList()
        assertEquals(2, reads.size, "the progress line and the restart point")
        for (at in reads) assertTrue(insideFrameSafeLambda(dock, at), "the bar reads the playhead in composition at $at")
        assertFalse(sheet.contains("position"), "the sheet knows nothing of the playhead")
        assertFalse(screen.contains("position.positionMs"), "the screen itself still never reads the playhead")
    }

    @Test fun theScrimFollowsTheProgressWhileDrawing() {
        val draw = media.indexOf("onDrawBehind {")
        val read = media.indexOf("ControlsSheetRules.bottomScrimStrength(controls?.value ?: 0f)")
        assertTrue(draw > 0 && read > draw, "the bottom scrim's strength is read in the draw lambda")
        assertTrue(screen.contains("controls = controlsSheet.progressState"), "the page hands the progress to the backdrop")
    }

    // ---- the video is not touched -----------------------------------------------------------------------------------------

    @Test fun nothingBlursOrSamplesTheVideo() {
        for ((name, src) in listOf("sheet" to sheet, "dock" to dock, "state" to state)) {
            assertFalse(src.contains("hazeEffect") || src.contains("optimizedHazeEffect") || src.contains("HazeState"), "$name has no Haze")
            assertFalse(Regex("""\.blur\(""").containsMatchIn(src), "$name has no blur")
            assertFalse(src.contains("systemGestureExclusion"), "$name never claims the system gesture areas")
        }
        assertTrue(dock.contains(".cosmoGlass(hazeState = null, shape = shape, overVideo = true)"), "the bar is the solid glass of layer C")
        // the backdrop's video is called as before: no layer around the clip, its alpha its own parameter
        assertTrue(media.contains("presentationAlpha = { reveal.value"))
        assertTrue(media.contains("contentMode = CanvasContentMode.CROP"), "same scaling and cropping")
    }

    // ---- accessibility ----------------------------------------------------------------------------------------------------

    @Test fun theHandleAndTheBarDescribeThemselves() {
        for ((name, src) in listOf("handle" to sheet, "bar" to dock)) {
            assertTrue(src.contains("role = Role.Button"), "$name is a button")
            assertTrue(src.contains("stateDescription = state"), "$name has a state description")
            assertTrue(src.contains("customActions = listOf(CustomAccessibilityAction("), "$name has a custom action")
        }
        for (key in listOf("cosmo_np_controls_minimize", "cosmo_np_controls_expand", "cosmo_np_controls_minimized_state", "cosmo_np_controls_expanded_state")) {
            assertTrue(sheet.contains("Res.string.$key"), "the handle says $key")
        }
        assertTrue(dock.contains("Res.string.cosmo_np_controls_expand") && dock.contains("Res.string.cosmo_np_controls_minimized_state"))
        // the bar's buttons are the app's buttons, which reserve a 48 dp target (minimumInteractiveComponentSize)
        assertTrue(dock.contains("CosmoIconButton(") && dock.contains("PlayMorphButton("))
    }

    // ---- the numbers of the brief -----------------------------------------------------------------------------------------

    @Test fun theNumbersAreTheBriefs() {
        val pins = listOf(
            "PILL_WIDTH_DP = 44", "PILL_HEIGHT_DP = 5", "HANDLE_TARGET_DP = 48",
            "DOCK_HEIGHT_DP = 72", "DOCK_MARGIN_DP = 12", "DOCK_RADIUS_DP = 24",
            "DOCK_THUMB_DP = 44", "DOCK_THUMB_RADIUS_DP = 11", "DOCK_TILE_DP = 38", "DOCK_PLAY_DP = 46", "DOCK_PROGRESS_DP = 4",
            "MINIMIZED_SCRIM_SHARE = 0.25f", "HALF = 0.5f",
        )
        for (pin in pins) assertTrue(state.contains("const val $pin"), pin)
        assertTrue(dock.contains("maxLines = 1, overflow = TextOverflow.Ellipsis"), "the title and the artist are one line each")
        assertTrue(dock.contains("type.title(TypeScale.TITLE_M)"), "the title in the display face")
        assertTrue(dock.contains("style = CosmoIconButtonStyle.TONAL, size = ControlsSheetRules.DOCK_TILE_DP.dp"), "the full transport's tonal tiles")
        assertTrue(sheet.contains(".navigationBarsPadding()"), "the bar sits above the navigation bar")
    }

    @Test fun reducedMotionSnapsAndTheRestIsTheMoveSpring() {
        assertTrue(state.contains("if (reduced) {\n            progress.snapTo(target)"), "reduced motion: an instant change")
        assertTrue(state.contains("spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness"), "the move spring")
        assertTrue(sheet.contains("val reduced = LocalReducedMotion.current"), "the app's reduced flag")
    }

    // ---- the seam in the page ---------------------------------------------------------------------------------------------

    @Test fun thePageWrapsTheSameStackCallAndAppliesOnlyWhileTheVideoShowsInPortrait() {
        assertTrue(screen.contains("stack = { m -> stack(m.onSizeChanged { stackHeightPx.intValue = it.height }, false, null) }"), "the stack is called as before")
        assertTrue(screen.contains("val controlsApply = videoShown && portrait && !panelOpen"))
        assertTrue(screen.contains("val portrait = layout == NowPlayingLayout.PORTRAIT"))
        assertTrue(screen.contains("enabled = controlsApply"))
        // the landscape and wide pages are not changed: the sheet is only in the portrait page
        assertEquals(1, Regex("""NowPlayingControlsSheet\(""").findAll(screen).count())
        val portraitPage = screen.substringAfter("val page: @Composable BoxScope.() -> Unit = {").substringBefore("val landscapePage")
        assertTrue(portraitPage.contains("NowPlayingControlsSheet("), "in the portrait page")
    }

    @Test fun notEnabledTheSheetIsTheStackAloneAndTheStackNeverChangesPlace() {
        // one call of the stack, after the handle's conditional group, so its state survives the handle coming and going
        assertEquals(1, Regex("""\bstack\(Modifier\)""").findAll(sheet).count())
        assertTrue(sheet.indexOf("if (enabled) ControlsHandle(") < sheet.indexOf("stack(Modifier)"))
        assertTrue(sheet.contains("} else {\n            Modifier\n        }"), "not enabled: no layer, no layout of its own")
    }
}
