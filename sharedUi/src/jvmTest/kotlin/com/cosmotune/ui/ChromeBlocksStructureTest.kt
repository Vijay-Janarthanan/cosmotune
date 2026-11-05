package com.cosmotune.ui

import com.cosmotune.design.Springs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no render can show, read from the source of the chrome blocks (the top bar, the pull indicator, the profile drawer and
 * its button): their signatures as the brief fixes them for the phone chrome, Home and the desktop shell to build on; the
 * drawer's radio group, in which a mode that does not work never becomes the selected one; the numbers of the motion and of
 * the scrim; the brand rule of the logo; and that the live palette and the per-frame values are read in draw-phase lambdas only.
 * The look itself is on the device and in the offscreen renders.
 */
class ChromeBlocksStructureTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome"
    private val topBar = SourceContract.read("$dir/CosmoTopBar.kt")
    private val pull = SourceContract.read("$dir/CosmoPullIndicator.kt")
    private val drawer = SourceContract.read("$dir/CosmoProfileDrawer.kt")
    private val files = listOf("CosmoTopBar" to topBar, "CosmoPullIndicator" to pull, "CosmoProfileDrawer" to drawer)

    // ---- the signatures other tasks build on ---------------------------------------------------------------------------------

    @Test fun theTopBarTakesTheParametersOfTheBriefInOrder() {
        val params = SourceContract.parameters(topBar, "CosmoTopBar")
        assertEquals(
            listOf(
                "title: String", "scrolled: Boolean", "height: Dp", "modifier: Modifier = Modifier", "trailingTitle: String? = null",
                "onBack: (() -> Unit)? = null", "refreshing: Boolean = false", "pullFraction: () -> Float = { 0f }",
                "accessory: (@Composable () -> Unit)? = null", "actions: @Composable () -> Unit = {}",
            ),
            params,
        )
    }

    @Test fun thePullIndicatorTakesTheParametersOfTheBriefInOrder() {
        assertEquals(
            listOf("fraction: () -> Float", "refreshing: Boolean", "modifier: Modifier = Modifier"),
            SourceContract.parameters(pull, "CosmoPullIndicator"),
        )
    }

    @Test fun theDrawerAndItsButtonTakeTheParametersOfTheBriefInOrder() {
        assertEquals(
            listOf(
                "open: Boolean", "onDismiss: () -> Unit", "name: String", "subtitle: String?", "avatarUrl: String?",
                "entries: List<CosmoDrawerEntry>", "onAccount: (() -> Unit)?", "modifier: Modifier = Modifier",
            ),
            SourceContract.parameters(drawer, "CosmoProfileDrawer"),
        )
        assertEquals(
            listOf(
                "avatarUrl: String?", "name: String", "subtitle: String?", "entries: List<CosmoDrawerEntry>", "onAccount: () -> Unit",
                "modifier: Modifier = Modifier", "size: Dp = 36.dp",
            ),
            SourceContract.parameters(drawer, "CosmoProfileButton"),
        )
    }

    @Test fun theDrawerEntryIsAnImmutableDataClassOfTheBrief() {
        val code = SourceContract.code(drawer).replace(Regex("\\s+"), " ")
        assertTrue(
            "@Immutable data class CosmoDrawerEntry(val icon: ImageVector, val label: String, val aside: String? = null, val onClick: () -> Unit)" in code,
            "the entry is the data class of the brief",
        )
    }

    // ---- the drawer's modes ---------------------------------------------------------------------------------------------------

    @Test fun theModesAreARadioGroupInWhichADisabledModeNeverBecomesSelected() {
        val code = SourceContract.code(drawer)
        assertTrue("Role.RadioButton" in code, "each mode is a radio button")
        assertTrue("selectableGroup()" in code, "in a selectable group")
        assertEquals(1, Regex("""\bselected\s*=\s*available\s*&&\s*mode\s*==\s*current""").findAll(code).count(), "selected needs the mode to be available")
        assertTrue(Regex("""val\s+available\s*=\s*AppModes\.available\(mode\)""").containsMatchIn(code), "availability comes from AppModes")
        // Y-3: the current mode is the stored one (Music only where there is no shell to ask), no longer hard-wired to Music.
        assertTrue(
            Regex("""val\s+current\s*=\s*settings\?\.mode\?\.collectAsState\(\)\?\.value\s*\?:\s*AppMode\.MUSIC""").containsMatchIn(code),
            "the current mode is the stored one, Music without a shell",
        )
        assertTrue("LocalCosmoShell.current?.settings" in code, "read from the shell's settings")
        // No other row may be handed `selected = true` or a selection that skips the check.
        val selectedArguments = Regex("""\bselected\s*=\s*([^,)\n]+)""").findAll(code).map { it.groupValues[1].trim() }.toList()
        assertTrue(selectedArguments.none { it == "true" }, "no mode is hard-wired to selected: $selectedArguments")
    }

    @Test fun aModeThatIsNotAvailableOpensTheLaterCardAndStaysUnselected() {
        val code = SourceContract.code(drawer)
        // Y-3: an available mode is stored, then the drawer closes (onChosen, formerly onChooseMusic); an unavailable one asks for the card.
        assertTrue(
            Regex("""onClick\s*=\s*\{\s*if\s*\(available\)\s*\{\s*settings\?\.setMode\(mode\);\s*onChosen\(\)\s*\}\s*else\s*onLater\(mode\)\s*\}""").containsMatchIn(code),
            "an unavailable mode asks for the card, an available one is stored",
        )
        assertTrue(Regex("""later\s*=\s*!available""").containsMatchIn(code), "and carries the Later tag")
        assertTrue("later = it" in code, "the card remembers which mode was asked for")
        // Both modes' card texts exist, and the board's notification switch is not built.
        for (key in listOf("cosmo_mode_video_later_title", "cosmo_mode_video_later_text", "cosmo_mode_library_later_title", "cosmo_mode_library_later_text")) {
            assertTrue("Res.string.$key" in code, "the card uses $key")
        }
        assertFalse("CosmoSwitch" in code, "there is no notification switch")
    }

    @Test fun aDisabledModeStaysFocusableAndSaysLater() {
        val code = SourceContract.code(drawer)
        val row = SourceContract.functionBody(drawer, "ModeRow")
        assertFalse(Regex("""enabled\s*=\s*(false|!?later|available)""").containsMatchIn(row), "the row is never disabled: it must stay focusable")
        assertTrue("LaterTag()" in row, "its text carries the tag, which the merged description reads")
        assertTrue("Res.string.cosmo_mode_later" in code, "the tag is the translated word")
    }

    @Test fun theCardsButtonIsTheSharedCloseButtonAndThereIsNoOtherWayToMakeAModeSelected() {
        val code = SourceContract.code(drawer)
        assertTrue(Regex("""CosmoButton\(\s*text\s*=\s*stringResource\(Res\.string\.cosmo_close\)""").containsMatchIn(code), "the card closes with cosmo_close")
        assertTrue("CosmoButtonKind.TONAL" in code, "a tonal button")
        assertEquals(0, Regex("""AppMode\.(VIDEO|LIBRARY)\s*->\s*true""").findAll(code).count())
    }

    // ---- the numbers ---------------------------------------------------------------------------------------------------------

    @Test fun theDrawerHasTheNumbersOfTheBoard() {
        val code = SourceContract.code(drawer)
        assertTrue(Regex("""const val ENTER_MS = 280\b""").containsMatchIn(code), "280 ms in")
        assertTrue(Regex("""const val EXIT_MS = 200\b""").containsMatchIn(code), "200 ms out")
        assertTrue(Regex("""const val SCRIM_ALPHA = 0\.56f""").containsMatchIn(code), "a scrim of 56 %")
        assertTrue(Regex("""val PANEL_WIDTH = 316\.dp""").containsMatchIn(code), "316 dp wide")
        assertTrue(Regex("""PANEL_MAX_SHARE = 0\.85f""").containsMatchIn(code), "at most 85 % of the window")
        assertTrue(Regex("""RoundedCornerShape\(topStart = 0\.dp, topEnd = 17\.dp, bottomEnd = 17\.dp, bottomStart = 0\.dp\)""").containsMatchIn(code), "end corners of 17 dp")
        assertTrue(Regex("""val DRAG_CLOSE = 64\.dp""").containsMatchIn(code), "a drag of 64 dp closes")
        assertEquals(150, Springs.REDUCED_MS, "reduced motion fades over 150 ms")
        assertTrue("Springs.REDUCED_MS" in code, "and the drawer takes it from there")
    }

    @Test fun theDrawerIsDrawnInAFullScreenWindowThatTheBackKeyAndEscapeClose() {
        val code = SourceContract.code(drawer)
        assertTrue(Regex("""DrawerWindow\(onDismissRequest = onDismiss\)""").containsMatchIn(code), "the drawer's window, which the Back key dismisses")
        assertFalse("Popup(" in code, "no popup of its own: a popup is capped at the visible display frame and stopped short of the navigation bar")
        assertTrue("Key.Escape" in code && "onPreviewKeyEvent" in code, "Escape does the same on the desktop")
        assertTrue(Regex("""paneTitle\s*=\s*profile""").containsMatchIn(code) && "Res.string.cosmo_profile" in code, "the panel is announced as the profile pane")
    }

    @Test fun onThePhoneTheWindowIsAnEdgeToEdgeDialogAndOnTheDesktopAPopupAtTheOrigin() {
        val common = SourceContract.code(SourceContract.read("$dir/DrawerWindow.kt")).replace(Regex("\\s+"), " ")
        assertTrue("internal expect fun DrawerWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit)" in common, "one declaration for both")
        val android = SourceContract.code(SourceContract.read("sharedUi/src/androidMain/kotlin/com/cosmotune/ui/chrome/DrawerWindow.android.kt")).replace(Regex("\\s+"), " ")
        assertTrue("Dialog(" in android && "Popup(" !in android, "a dialog window on the phone")
        for (property in listOf("dismissOnBackPress = true", "dismissOnClickOutside = false", "usePlatformDefaultWidth = false", "decorFitsSystemWindows = false")) {
            assertTrue(property in android, "the dialog has $property: edge to edge, behind the status bar and the navigation bar")
        }
        assertTrue("window.setDimAmount(0f)" in android && "FLAG_DIM_BEHIND" in android, "the platform's dim is off: the drawer's scrim is the only one")
        assertTrue("window.isNavigationBarContrastEnforced = false" in android, "and the system draws no contrast scrim over the navigation bar")
        val jvm = SourceContract.code(SourceContract.read("sharedUi/src/jvmMain/kotlin/com/cosmotune/ui/chrome/DrawerWindow.jvm.kt")).replace(Regex("\\s+"), " ")
        assertTrue(Regex("""Popup\( popupPositionProvider = WindowOrigin""").containsMatchIn(jvm), "a popup at the window's origin on the desktop")
        assertTrue("PopupProperties(focusable = true, dismissOnBackPress = true" in jvm, "focusable, so it receives the back key")
        assertTrue("onDismissRequest = onDismissRequest" in jvm, "which calls onDismiss")
        assertTrue("): IntOffset = IntOffset.Zero" in jvm, "positioned at 0,0")
    }

    @Test fun thePanelRunsTheWholeHeightAndItsContentKeepsClearOfTheBarsByTheSharedMetrics() {
        val layer = SourceContract.code(SourceContract.functionBody(drawer, "DrawerLayer")).replace(Regex("\\s+"), " ")
        val chain = layer.substringAfter("Column( Modifier .align(Alignment.CenterStart)")
        assertTrue(".fillMaxHeight()" in chain.substringBefore(".cosmoGlass("), "the panel is as tall as the window")
        assertTrue(chain.indexOf(".cosmoGlass(") < chain.indexOf(".padding("), "the glass fills the panel, bars included; the padding is inside it")
        assertTrue("top = DrawerMetrics.panelTopPaddingDp(statusTop.value).dp" in chain, "8 dp under the status bar")
        assertTrue("bottom = DrawerMetrics.panelBottomPaddingDp(navBottom.value).dp" in chain, "28 dp over the bottom, or the navigation bar when it is taller")
    }

    @Test fun theDrawerStaysMountedThroughItsExitAndCloseIsReducedToAFade() {
        val body = SourceContract.functionBody(drawer, "CosmoProfileDrawer")
        assertTrue(Regex("""derivedStateOf\s*\{\s*open\s*\|\|\s*slide\.value\s*>\s*0f\s*\}""").containsMatchIn(body), "composed until the slide is back at 0")
        assertTrue("if (!shown) return" in body)
        assertTrue("reduced -> tween<Float>(REDUCED_MS)" in body, "reduced motion fades")
        val layer = SourceContract.functionBody(drawer, "DrawerLayer")
        assertTrue("alpha = if (reduced) slide.value else 1f" in layer, "and does not slide")
    }

    // ---- the top bar ------------------------------------------------------------------------------------------------------------

    @Test fun theBackButtonIsAGlassIconButtonOf40dp() {
        val code = SourceContract.code(topBar)
        assertTrue(Regex("""CosmoIconButton\(\s*icon\s*=\s*CosmoIcons\.ArrowLeft""").containsMatchIn(code), "the arrow")
        assertTrue("style = CosmoIconButtonStyle.GLASS" in code, "C: it floats over the page")
        assertTrue(Regex("""size\s*=\s*40\.dp""").containsMatchIn(code), "40 dp")
        assertTrue("Res.string.cosmo_back" in code, "named Back")
    }

    @Test fun theLogoFollowsTheBrandRule() {
        val code = SourceContract.code(topBar)
        assertTrue(
            Regex("""fun brandMarkColor\(dark: Boolean\): Color = Color\(if \(dark\) CosmoBrand\.APRICOT else CosmoBrand\.INK\)""").containsMatchIn(code),
            "apricot on dark, ink on light",
        )
        assertTrue("brandMarkColor(LocalCosmoColors.current.dark)" in code, "the top bar asks it with the theme it is in")
        assertTrue("brandMarkColor(colors.dark)" in SourceContract.code(drawer), "so does the drawer's foot")
        assertTrue("Res.drawable.ic_logo" in code, "the mark is ic_logo")
        assertTrue(Regex("""LOGO_WIDTH\s*=\s*28\.dp""").containsMatchIn(code) && Regex("""LOGO_HEIGHT\s*=\s*18\.dp""").containsMatchIn(code), "28 x 18 dp")
    }

    @Test fun theTitleFadesInOnTheEffectsSpringAndTheBarHasTheBoardsSpacing() {
        val code = SourceContract.code(topBar)
        assertTrue("scrolled || trailingTitle != null" in code, "visible once scrolled, or with a trailing title")
        assertTrue("CosmoMotion.effects(reduced)" in code, "on the effects spring")
        assertTrue("graphicsLayer { alpha = titleAlpha.value }" in code, "read in the layer, not in composition")
        assertTrue(Regex("""SIDE\s*=\s*16\.dp""").containsMatchIn(code) && Regex("""TOP\s*=\s*4\.dp""").containsMatchIn(code) && Regex("""GAP\s*=\s*8\.dp""").containsMatchIn(code), "padding 4 16 0, gap 8")
        assertTrue(Regex("""ACTIONS_GAP\s*=\s*6\.dp""").containsMatchIn(code), "actions 6 dp apart")
        assertTrue("statusBarsPadding()" in code && ".height(height)" in code, "the whole height is the caller's, with the status bar in it")
        assertTrue("CosmoPullIndicator(fraction = pullFraction, refreshing = refreshing)" in code, "the pull indicator hangs under the bar")
    }

    // ---- the pull indicator --------------------------------------------------------------------------------------------------------

    @Test fun theIndicatorTakesNoHeightAndShowsNothingAtRest() {
        val code = SourceContract.code(pull)
        assertTrue("layout(placeable.width, 0)" in code, "zero height: the bar keeps its size")
        assertTrue("if (!refreshing && !visible) return" in code, "not drawn while the fraction is 0 and nothing refreshes")
        assertTrue("PullIndicator.TRAVEL_DP.dp.toPx()" in code, "it travels fraction x 64 dp")
        assertTrue("LoadingBlob.PERIOD_MS" in code, "the loading loop is the shared 1.1 s one")
        assertTrue("phase?.value ?: 0.5f" in code, "reduced motion: the still circle half way through the loop")
        assertTrue(Regex("""if\s*\(refreshing\)\s*\{\s*contentDescription\s*=\s*label""").containsMatchIn(code) && "Res.string.cosmo_refreshing" in code, "named Refreshing while it refreshes")
    }

    @Test fun thePullFractionIsReadOnlyInLayerAndDrawBlocks() {
        // `derivedStateOf` changes the composition only when its boolean flips.
        val openers = PER_FRAME_OPENERS + Regex("""derivedStateOf\s*\{""")
        assertReadOnlyIn("CosmoPullIndicator", pull, Regex("""\bcurrentFraction\(\)"""), openers)
        assertReadOnlyIn("CosmoPullIndicator", pull, Regex("""\bheld\.value"""), openers)
        assertReadOnlyIn("CosmoPullIndicator", pull, Regex("""\bphase\??\.value"""), openers)
    }

    @Test fun theDrawersSlideAndDragAreReadOnlyInLayerAndDrawBlocks() {
        val openers = PER_FRAME_OPENERS + Regex("""derivedStateOf\s*\{""")
        assertReadOnlyIn("CosmoProfileDrawer", drawer, Regex("""\bslide\.value"""), openers)
        assertReadOnlyIn("CosmoProfileDrawer", drawer, Regex("""\bappear\.value"""), openers)
        assertReadOnlyIn("CosmoTopBar", topBar, Regex("""\btitleAlpha\.value"""), openers)
    }

    // ---- the palette ---------------------------------------------------------------------------------------------------------

    @Test fun liveColoursAreReadOnlyInDrawPhaseLambdas() {
        for ((name, src) in files) assertReadOnlyIn(name, src, Regex("""\.live\("""), PER_FRAME_OPENERS)
    }

    @Test fun noBrushOrLiveColourIsRememberedAtCreation() {
        // The designer's round-1 bug: a brush made once at theme creation makes every control the fallback apricot.
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\()""")
        for ((name, src) in files) {
            val bad = remembering.find(SourceContract.code(src))
            assertTrue(bad == null, "$name remembers a brush or a live colour: ${bad?.value}")
        }
    }

    @Test fun noColourIsChosenByHandExceptTheBlackOfScrimsAndShadows() {
        for ((name, src) in files) {
            val code = SourceContract.code(src)
            assertFalse(Regex("""Color\(\s*0x""").containsMatchIn(code), "$name has a colour literal")
            assertFalse(Regex("""Color\.(White|Red|Gray|Blue|Green)\b""").containsMatchIn(code), "$name uses a named colour")
            for (match in Regex("""Color\.Black[^\n]*""").findAll(code)) {
                val line = match.value
                assertTrue("SCRIM_ALPHA" in line || "CARD_SCRIM_ALPHA" in line || "spot" in line || "ambient" in line, "$name: black is for scrims and shadows only: $line")
            }
        }
    }

    @Test fun everyStringTheBlocksUseExistsInEnglishAndNoTextIsHardWired() {
        val english = SourceContract.read("sharedUi/src/commonMain/composeResources/values/strings.xml")
        for ((name, src) in files) {
            val code = SourceContract.code(src)
            for (key in Regex("""Res\.string\.(\w+)""").findAll(code).map { it.groupValues[1] }.toSet()) {
                assertTrue("""<string name="$key">""" in english, "$name uses Res.string.$key, which strings.xml does not have")
            }
            assertFalse(Regex("""Text\(\s*(text\s*=\s*)?"[A-Za-z]""").containsMatchIn(code), "$name has a hard-wired text")
        }
    }

    @Test fun theProfileButtonOwnsItsDrawerAndEveryWayOutClosesIt() {
        val body = SourceContract.functionBody(drawer, "CosmoProfileButton")
        assertTrue("remember { mutableStateOf(false) }" in body, "it keeps whether the drawer is open")
        assertTrue("open = true" in body, "a click opens it")
        assertTrue("entry.copy(onClick = { open = false; entry.onClick() })" in body, "an entry closes it, then does its thing")
        assertTrue("onAccount = { open = false; onAccount() }" in body, "the head closes it, then opens the account")
        assertTrue("onDismiss = { open = false }" in body, "so does the scrim")
        assertTrue("contentDescription = label" in body && "Res.string.cosmo_profile" in body, "the button is named Profile and modes")
        assertTrue("minimumInteractiveComponentSize()" in body, "in a 48 dp target")
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    /** The lambdas of the draw phase and the layer phase. */
    private val PER_FRAME_OPENERS = listOf(
        Regex("""drawBehind\s*\{"""), Regex("""drawWithContent\s*\{"""), Regex("""drawWithCache\s*\{"""), Regex("""graphicsLayer\s*\{"""),
        Regex("""Canvas\(.*\)\s*\{"""), Regex("""onDrawWithContent\s*\{"""),
    )

    /** Fails if [what] is read anywhere in [src] but inside the body of one of [openers]'s lambdas. */
    private fun assertReadOnlyIn(name: String, src: String, what: Regex, openers: List<Regex>) {
        val text = SourceContract.code(src)
        for (match in what.findAll(text)) {
            val at = match.range.first
            val line = text.lineSequence().drop(text.substring(0, at).count { it == '\n' }).first().trim()
            val inside = openers.any { opener ->
                opener.findAll(text.substring(0, at)).lastOrNull()?.let { open ->
                    // the lambda is still open at `at` when the braces since its `{` have not closed it
                    var depth = 1
                    for (i in open.range.last + 1 until at) {
                        when (text[i]) { '{' -> depth++; '}' -> depth-- }
                        if (depth == 0) return@let false
                    }
                    true
                } ?: false
            }
            assertTrue(inside, "$name reads `${match.value}` outside a draw-phase lambda: $line")
        }
    }
}
