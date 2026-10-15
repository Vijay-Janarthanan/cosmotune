package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The widget bridge (`com.cosmotune.ui.bridge`: Switch, CircularProgressIndicator, AlertDialog, ModalBottomSheet, DropdownMenu,
 * DropdownMenuItem), pinned by reading the source (there is no Compose UI test harness here and none is to be added): the 29
 * upstream files take each widget by the import swap and nothing else, every forwarder has Material's parameter list and passes
 * every parameter by name when the look is off, and the new look's numbers are the spec's.
 */
class BridgeContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")
    private fun bridge(name: String): String = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/bridge/$name")

    /** The file without its comments (blocks and line comments), so a rule about code is not tripped by a sentence about it. */
    private fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    /** The parameters of the [nth] `fun [name](...)` in [src], each as `name: type = default` with its white space collapsed. */
    private fun parameters(src: String, name: String, nth: Int = 0): List<String> {
        val text = code(src).replace("->", "→")
        var start = -1
        repeat(nth + 1) { start = text.indexOf("fun $name(", start + 1) }
        assertTrue(start >= 0, "fun $name( (number $nth) is in the file")
        val open = text.indexOf('(', start)
        var depth = 0
        var close = -1
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { close = i; break }
            }
            if (close >= 0) break
        }
        assertTrue(close > open, "the parameter list of $name is closed")
        val inside = text.substring(open + 1, close)
        val parts = ArrayList<String>()
        var level = 0
        var from = 0
        for (i in inside.indices) {
            when (inside[i]) {
                '(', '<', '{' -> level++
                ')', '>', '}' -> level--
                ',' -> if (level == 0) { parts += inside.substring(from, i); from = i + 1 }
            }
        }
        parts += inside.substring(from)
        return parts.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun names(params: List<String>): List<String> = params.map { it.substringBefore(":").trim() }

    /** The body of the [nth] `fun [name](` of [src] (code only), between its braces. */
    private fun body(src: String, name: String, nth: Int = 0): String {
        val text = code(src)
        var start = -1
        repeat(nth + 1) { start = text.indexOf("fun $name(", start + 1) }
        assertTrue(start >= 0, "fun $name( (number $nth) is in the file")
        val open = text.indexOf(") {\n", start) + 2
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        error("fun $name( is not closed")
    }

    /** What a forwarder does with the look off: from its first `if (LocalCosmoShell.current == null` to the `return` that ends it. */
    private fun lookOff(fn: String): String {
        val from = fn.indexOf("if (LocalCosmoShell.current == null")
        assertTrue(from >= 0, "the forwarder opens with the look-off test")
        val until = fn.indexOf("return", from)
        assertTrue(until > from, "the look-off branch returns")
        return fn.substring(from, until)
    }

    /** What a forwarder does with the look on: everything after the look-off branch. */
    private fun lookOn(fn: String): String = fn.substring(fn.indexOf("return", fn.indexOf("if (LocalCosmoShell.current == null")) + "return".length)

    private fun assertPassesEveryParameterByName(branch: String, params: List<String>, what: String) {
        for (name in names(params)) {
            assertTrue(Regex("""\b$name = $name\b""").containsMatchIn(branch), "$what passes $name = $name")
        }
    }

    // ---- the import swaps -----------------------------------------------------------------------------------------------

    private val app = "app/src/main/java/com/music/bitchord/"
    private val desktop = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/"

    private val swapped: Map<String, List<String>> = mapOf(
        "Switch" to listOf("AccountAndScrobblingScreen", "DiscordScreen", "EqualizerScreen", "ListenTogetherScreen", "SettingsSheet", "SourcesScreen", "SpotifyCanvasAuthScreen")
            .map { "${app}ui/screens/$it.kt" } +
            listOf("DesktopApp", "DesktopEqualizerUi", "DesktopIntegrationsDialog", "DesktopListenTogetherUi").map { "$desktop$it.kt" },
        "CircularProgressIndicator" to listOf("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt") +
            listOf("AccountChannelDialog", "CastDialog", "DownloadManagerSheet", "PlaylistPickerSheet", "ReorderPlaylistSheet", "SongActionsSheet")
                .map { "${app}ui/components/$it.kt" } +
            listOf("ListenTogetherScreen", "SpotifyLibraryScreen").map { "${app}ui/screens/$it.kt" } +
            listOf("ReplayScreen", "ReplayShareSheet").map { "${app}ui/replay/$it.kt" } +
            listOf("DesktopAccountUi", "DesktopApp", "DesktopIntegrationsUi", "DesktopListenTogetherUi", "DesktopPlaylistUi", "DesktopSongMenuUi")
                .map { "$desktop$it.kt" },
        "AlertDialog" to listOf("${app}ui/screens/ListenTogetherScreen.kt", "${app}ui/screens/SettingsSheet.kt", "${app}ui/components/LyricsSourcesDialog.kt", "${desktop}DesktopApp.kt"),
        "ModalBottomSheet" to listOf("${app}MainActivity.kt") + listOf("EqualizerScreen", "ListenTogetherScreen", "SettingsSheet").map { "${app}ui/screens/$it.kt" },
        "DropdownMenu" to listOf("${app}MainActivity.kt", "${app}ui/screens/LocalMusicScreen.kt", "${desktop}DesktopApp.kt", "${desktop}DesktopSharedPages.kt"),
        "DropdownMenuItem" to listOf("${app}MainActivity.kt", "${app}ui/screens/LocalMusicScreen.kt", "${desktop}DesktopApp.kt", "${desktop}DesktopSharedPages.kt"),
    )

    @Test fun everyListedFileTakesTheWidgetFromTheBridgeAndNotFromMaterial() {
        for ((widget, files) in swapped) {
            for (file in files) {
                val src = read(file)
                assertTrue(
                    Regex("""^import com\.cosmotune\.ui\.bridge\.$widget$""", RegexOption.MULTILINE).containsMatchIn(src),
                    "$file imports com.cosmotune.ui.bridge.$widget",
                )
                assertFalse(
                    Regex("""^import androidx\.compose\.material3\.$widget$""", RegexOption.MULTILINE).containsMatchIn(src),
                    "$file no longer imports androidx.compose.material3.$widget",
                )
                assertFalse(Regex("""^import androidx\.compose\.material3\.\*""", RegexOption.MULTILINE).containsMatchIn(src), "$file has no material3 wildcard")
                assertEquals(1, Regex("""^import .*\.$widget$""", RegexOption.MULTILINE).findAll(src).count(), "$file imports $widget once")
            }
        }
    }

    @Test fun theSwapsTouchTwentyNineFilesAndFortyFourImports() {
        assertEquals(29, swapped.values.flatten().toSet().size)
        assertEquals(44, swapped.values.sumOf { it.size })
    }

    @Test fun theCallsInThoseFilesAreNotQualifiedAndNotRenamed() {
        // the swap changes the import and nothing else: no call site names the bridge or Material by its full path
        for (file in swapped.values.flatten().toSet()) {
            val src = code(read(file))
            assertFalse(src.contains("androidx.compose.material3.Switch("), "$file")
            assertFalse(src.contains("com.cosmotune.ui.bridge.") && src.lines().any { it.contains("com.cosmotune.ui.bridge.") && !it.startsWith("import ") }, "$file")
        }
    }

    @Test fun theFilesLeftOutOnPurposeStillTakeMaterialsOwn() {
        val left = listOf(
            "${app}ui/components/MiniPlayer.kt", "${app}ui/components/GlassNavBar.kt", "${app}ui/components/FrostedTopBar.kt",
            "${app}ui/player/AndroidLyricsShareSheet.kt",
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt",
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt",
            "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsProviderSheet.kt",
        )
        for (file in left) {
            val src = read(file)
            assertTrue(src.contains("import androidx.compose.material3.CircularProgressIndicator\n"), "$file keeps Material's spinner")
            assertFalse(src.contains("com.cosmotune.ui.bridge"), "$file does not use the bridge")
        }
    }

    // ---- the signatures ---------------------------------------------------------------------------------------------------

    private val switchParameters = listOf("checked", "onCheckedChange", "modifier", "thumbContent", "enabled", "colors", "interactionSource")
    private val indeterminateParameters = listOf("modifier", "color", "strokeWidth", "trackColor", "strokeCap")
    private val determinateParameters = listOf("progress", "modifier", "color", "strokeWidth", "trackColor", "strokeCap", "gapSize")
    private val dialogParameters = listOf(
        "onDismissRequest", "confirmButton", "modifier", "dismissButton", "icon", "title", "text", "shape", "containerColor",
        "iconContentColor", "titleContentColor", "textContentColor", "tonalElevation", "properties",
    )
    private val sheetParameters = listOf(
        "onDismissRequest", "modifier", "sheetState", "sheetMaxWidth", "shape", "containerColor", "contentColor", "tonalElevation",
        "scrimColor", "dragHandle", "contentWindowInsets", "properties", "content",
    )
    private val menuParameters = listOf(
        "expanded", "onDismissRequest", "modifier", "offset", "scrollState", "properties", "shape", "containerColor", "tonalElevation",
        "shadowElevation", "border", "content",
    )
    private val menuItemParameters = listOf("text", "onClick", "modifier", "leadingIcon", "trailingIcon", "enabled", "colors", "contentPadding", "interactionSource")

    @Test fun everyForwarderHasMaterialsParameterNamesInMaterialsOrder() {
        assertEquals(switchParameters, names(parameters(bridge("SwitchBridge.kt"), "Switch")))
        assertEquals(indeterminateParameters, names(parameters(bridge("ProgressBridge.kt"), "CircularProgressIndicator", 0)))
        assertEquals(determinateParameters, names(parameters(bridge("ProgressBridge.kt"), "CircularProgressIndicator", 1)))
        assertEquals(dialogParameters, names(parameters(bridge("DialogBridge.kt"), "AlertDialog")))
        assertEquals(sheetParameters, names(parameters(bridge("SheetBridge.kt"), "ModalBottomSheet")))
        assertEquals(menuParameters, names(parameters(bridge("MenuBridge.kt"), "DropdownMenu")))
        assertEquals(menuItemParameters, names(parameters(bridge("MenuBridge.kt"), "DropdownMenuItem")))
    }

    @Test fun theDefaultsAreMaterialsOwn() {
        val switch = parameters(bridge("SwitchBridge.kt"), "Switch")
        assertEquals(
            listOf("thumbContent: (@Composable () → Unit)? = null", "enabled: Boolean = true", "colors: SwitchColors = SwitchDefaults.colors()", "interactionSource: MutableInteractionSource? = null"),
            switch.drop(3),
        )
        val sheet = parameters(bridge("SheetBridge.kt"), "ModalBottomSheet")
        assertTrue("tonalElevation: Dp = 0.dp" in sheet)
        assertTrue("dragHandle: @Composable (() → Unit)? = { BottomSheetDefaults.DragHandle() }" in sheet)
        assertTrue("contentWindowInsets: @Composable () → WindowInsets = { BottomSheetDefaults.windowInsets }" in sheet)
        assertTrue("properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties" in sheet)
        val menu = parameters(bridge("MenuBridge.kt"), "DropdownMenu")
        assertTrue("properties: PopupProperties = PopupProperties(focusable = true)" in menu)
        assertTrue("border: BorderStroke? = null" in menu)
        assertTrue("shadowElevation: Dp = MenuDefaults.ShadowElevation" in menu)
        val dialog = parameters(bridge("DialogBridge.kt"), "AlertDialog")
        assertTrue("properties: DialogProperties = DialogProperties()" in dialog)
        assertTrue("tonalElevation: Dp = AlertDialogDefaults.TonalElevation" in dialog)
    }

    // ---- the look-off branch --------------------------------------------------------------------------------------------------

    private data class Forwarder(val file: String, val name: String, val nth: Int, val material: String)

    private val forwarders = listOf(
        Forwarder("SwitchBridge.kt", "Switch", 0, "androidx.compose.material3.Switch("),
        Forwarder("ProgressBridge.kt", "CircularProgressIndicator", 0, "androidx.compose.material3.CircularProgressIndicator("),
        Forwarder("ProgressBridge.kt", "CircularProgressIndicator", 1, "androidx.compose.material3.CircularProgressIndicator("),
        Forwarder("DialogBridge.kt", "AlertDialog", 0, "androidx.compose.material3.AlertDialog("),
        Forwarder("SheetBridge.kt", "ModalBottomSheet", 0, "androidx.compose.material3.ModalBottomSheet("),
        Forwarder("MenuBridge.kt", "DropdownMenu", 0, "androidx.compose.material3.DropdownMenu("),
        Forwarder("MenuBridge.kt", "DropdownMenuItem", 0, "androidx.compose.material3.DropdownMenuItem("),
    )

    @Test fun withTheLookOffEveryForwarderCallsMaterialsOwnWithEveryParameterByName() {
        for (f in forwarders) {
            val src = bridge(f.file)
            val fn = body(src, f.name, f.nth)
            val off = lookOff(fn)
            assertTrue(fn.trimStart().startsWith("if (LocalCosmoShell.current == null"), "${f.name} #${f.nth} starts with the look-off test")
            assertTrue(off.contains(f.material), "${f.name} #${f.nth} calls ${f.material}")
            assertPassesEveryParameterByName(off, parameters(src, f.name, f.nth), "${f.name} #${f.nth}")
        }
    }

    @Test fun theLookOffBranchPassesTheCallersValuesAsTheyAre() {
        // every argument of the Material call is `name = name`: nothing is computed on the way when the look is off
        for (f in forwarders) {
            val off = lookOff(body(bridge(f.file), f.name, f.nth))
            val arguments = off.substringAfter(f.material).substringBeforeLast(")")
            for (line in arguments.lines().map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }) {
                val (key, value) = line.split(" = ").also { assertEquals(2, it.size, "${f.name}: $line is `name = value`") }
                assertEquals(key, value, "${f.name} #${f.nth}: $line")
            }
        }
    }

    @Test fun theSheetPassesThroughWhenTheCallerPaintsItselfTransparent() {
        val src = bridge("SheetBridge.kt")
        val fn = body(src, "ModalBottomSheet")
        assertTrue(fn.trimStart().startsWith("if (LocalCosmoShell.current == null || containerColor == Color.Transparent)"), "the transparent container takes the look-off branch")
    }

    // ---- the new look ---------------------------------------------------------------------------------------------------------

    @Test fun theSwitchIsTheBSwitchAndADisplayOnlySwitchLeavesTheClickToItsRow() {
        val on = lookOn(body(bridge("SwitchBridge.kt"), "Switch"))
        assertTrue(on.contains("CosmoSwitch("))
        assertTrue(on.contains("onCheckedChange = { onCheckedChange?.invoke(it) }"))
        assertTrue(on.contains("enabled = enabled && onCheckedChange != null"))
        assertTrue(on.contains("modifier.clearAndSetSemantics {}"))
        assertFalse(on.contains("thumbContent") || on.contains("colors") || on.contains("interactionSource"), "the palette decides: those three are ignored")
    }

    @Test fun theSpinnerIsTheLoadingBlobInTheCallersColourUnlessItIsMaterialsDefault() {
        val on = lookOn(body(bridge("ProgressBridge.kt"), "CircularProgressIndicator", 0))
        assertTrue(on.contains("CosmoLoadingBlob(modifier,"))
        assertTrue(on.contains("if (color == ProgressIndicatorDefaults.circularColor) Color(LocalCosmoColors.current.palette.accent) else color"))
        for (ignored in listOf("strokeWidth", "trackColor", "strokeCap")) assertFalse(on.contains(ignored), "$ignored describes a ring")
        val blob = code(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/CosmoLoadingBlob.kt"))
        assertTrue(blob.contains("fun CosmoLoadingBlob(modifier: Modifier = Modifier, color: Color = Color.Unspecified)"))
        assertTrue(blob.contains("DEFAULT_DIAMETER = 40.dp"), "Material's CircularIndicatorDiameter")
        assertTrue(blob.contains(".size(DEFAULT_DIAMETER)"), "the caller's size comes first and wins")
        assertTrue(blob.contains("LocalReducedMotion.current"), "reduced motion shows a still circle (LoadingBlobShape's `reduced`)")
        assertTrue(blob.contains("ProgressBarRangeInfo.Indeterminate"))
        assertFalse(blob.contains("live("), "composition-time colours only")
    }

    @Test fun theDeterminateRingIsMaterialsWithThePalettesAccentAndTrack() {
        val on = lookOn(body(bridge("ProgressBridge.kt"), "CircularProgressIndicator", 1))
        assertTrue(on.contains("androidx.compose.material3.CircularProgressIndicator("))
        assertTrue(on.contains("Color(palette.accent)"))
        assertTrue(on.contains("Color(palette.track)"))
    }

    @Test fun theDialogIsA36dpSolidTintedGlassSuperellipseWithNoTonalElevation() {
        val src = bridge("DialogBridge.kt")
        assertTrue(code(src).contains("SuperellipseShape(CosmoRadii.sheet)"))
        assertTrue(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoTokens.kt").contains("val sheet = 36.dp"))
        val on = lookOn(body(src, "AlertDialog"))
        for (line in listOf(
            "shape = DIALOG_SHAPE",
            "containerColor = Color(palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA)",
            "iconContentColor = Color(palette.accent)",
            "titleContentColor = Color(palette.textPrimary)",
            "textContentColor = Color(palette.textSecondary)",
            "tonalElevation = 0.dp",
        )) assertTrue(on.contains(line), line)
        assertFalse(on.contains("live("))
    }

    @Test fun theSheetIsDetachedSolidTintedGlassWithTheInkGrabber() {
        val src = bridge("SheetBridge.kt")
        val c = code(src)
        assertTrue(c.contains("SuperellipseShape(CosmoRadii.sheet)"))
        assertTrue(c.contains("Color.Black.copy(alpha = 0.40f)"), ".scrim-dim")
        val on = lookOn(body(src, "ModalBottomSheet"))
        for (line in listOf(
            "modifier = modifier.padding(horizontal = CosmoRadii.sheetInset)",
            "shape = SHEET_SHAPE",
            "containerColor = glass",
            "tonalElevation = 0.dp",
            "scrimColor = SHEET_SCRIM",
            "dragHandle = if (dragHandle == null) null else { { CosmoSheetHandle() } }",
        )) assertTrue(on.contains(line), line)
        assertTrue(on.contains("Color(LocalCosmoColors.current.palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA)"))
        val tokens = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoTokens.kt")
        assertTrue(tokens.contains("val sheetInset = 8.dp"))
        // the grabber: 36 x 4 dp, textPrimary at 45 %, 10 dp above and 14 dp below
        assertTrue(c.contains("GRAB_WIDTH = 36.dp") && c.contains("GRAB_HEIGHT = 4.dp") && c.contains("GRAB_ALPHA = 0.45f"))
        assertTrue(c.contains("GRAB_TOP = 10.dp") && c.contains("GRAB_BOTTOM = 14.dp"))
        assertTrue(c.contains("Color(LocalCosmoColors.current.palette.textPrimary).copy(alpha = GRAB_ALPHA)"))
        assertTrue(c.contains("fun CosmoSheetHandle(modifier: Modifier = Modifier)"))
    }

    @Test fun theMenuAndItsItemsAreRoundedGlass() {
        val src = bridge("MenuBridge.kt")
        val c = code(src)
        assertTrue(c.contains("SuperellipseShape(CosmoRadii.lg)"))
        assertTrue(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoTokens.kt").contains("val lg = 22.dp"))
        val menu = lookOn(body(src, "DropdownMenu"))
        for (line in listOf(
            "shape = MENU_SHAPE",
            "containerColor = Color(LocalCosmoColors.current.palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA)",
            "tonalElevation = 0.dp",
            "shadowElevation = GlassPolicy.SHADOW_ELEVATION_DP.dp",
            "border = null",
        )) assertTrue(menu.contains(line), line)
        val item = lookOn(body(src, "DropdownMenuItem"))
        assertTrue(item.contains("modifier = modifier.padding(horizontal = ITEM_INSET).clip(ITEM_SHAPE)"))
        assertTrue(c.contains("ITEM_SHAPE = RoundedCornerShape(14.dp)") && c.contains("ITEM_INSET = 6.dp"))
    }

    @Test fun nothingInTheBridgeReadsALiveColour() {
        // these are composition-time parameters: `live(` belongs to the draw phase
        for (name in listOf("SwitchBridge.kt", "ProgressBridge.kt", "DialogBridge.kt", "SheetBridge.kt", "MenuBridge.kt")) {
            assertFalse(code(bridge(name)).contains(".live("), name)
        }
    }

    @Test fun theBridgeFilesDeclareOnlyTheirOwnWidgetsInThePackageTheImportsName() {
        for (name in listOf("SwitchBridge.kt", "ProgressBridge.kt", "DialogBridge.kt", "SheetBridge.kt", "MenuBridge.kt")) {
            assertTrue(bridge(name).startsWith("package com.cosmotune.ui.bridge\n"), name)
        }
    }

    @Test fun theLoadingBlobShapeIsInternalSoTheSpinnerCanUseIt() {
        val src = code(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/PlayMorphButton.kt"))
        assertTrue(src.contains("internal fun LoadingBlobShape(size: Dp, color: Color, reduced: Boolean)"))
        assertFalse(src.contains("private fun LoadingBlobShape("))
        assertTrue(code(read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/CosmoLoadingBlob.kt")).contains("LoadingBlobShape("))
    }

    // ---- the fork table -----------------------------------------------------------------------------------------------------

    @Test fun theForkTableHasOneRowForTheImportSwapsAndEveryRowHasFourColumns() {
        val rows = read("FORK.md").lines().filter { it.startsWith("|") }
        assertTrue(rows.any { it.contains("com.cosmotune.ui.bridge.") && it.contains("the import of `X`") }, "the bridge row")
        for (row in rows) {
            // a `|` inside a code span is escaped as `\|`; the columns are what is left
            val columns = row.trim().removeSurrounding("|").split(Regex("""(?<!\\)\|"""))
            assertEquals(4, columns.size, "four columns: ${row.take(60)}")
        }
    }
}
