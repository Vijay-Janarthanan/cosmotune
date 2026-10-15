package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no render can show, read from the sources of the sheets, menus, dialogs and toasts (`com.cosmotune.ui.sheets`) and of the
 * upstream sites that take them: the delegates (each is the first statement of its function, or the named condition, and names
 * every parameter it uses), the menu style passed through to the rows, the sheet that never takes a `HazeState` (E7: a sheet opens
 * in its own window and draws the solid glass), the draw-phase rule for the live palette, and the numbers of the spec.
 */
class SheetsContractTest {
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/sheets"
    private fun sheet(name: String): String = SourceContract.read("$dir/$name.kt")
    private val files = listOf(
        "CosmoSheetSurface", "CosmoActionRow", "CosmoSheetHeader", "CosmoToast", "CosmoMenuSurface", "CosmoDialogCard", "CosmoSheetPalette",
    )

    private val phone = "app/src/main/java/com/music/bitchord/ui/components"
    private val desktop = "desktopApp/src/main/kotlin/com/music/bitchord/desktop"
    private val songSheet = SourceContract.read("$phone/SongActionsSheet.kt")
    private val browseSheet = SourceContract.read("$phone/BrowseActionsSheet.kt")
    private val notice = SourceContract.read("$phone/QueueActionNotice.kt")
    private val picker = SourceContract.read("$phone/PlaylistPickerSheet.kt")
    private val held = SourceContract.read("$phone/HeldContextMenu.kt")
    private val surfaces = SourceContract.read("$desktop/DesktopSurfaces.kt")
    private val playlistUi = SourceContract.read("$desktop/DesktopPlaylistUi.kt")
    private val songMenu = SourceContract.read("$desktop/DesktopSongMenuUi.kt")
    private val desktopApp = SourceContract.read("$desktop/DesktopApp.kt")

    private val look = "if (com.cosmotune.ui.theme.LocalCosmoShell.current != null)"
    private val menuStyle = "menu = LocalActionRowStyle.current == ActionRowStyle.Menu"

    private fun firstStatement(src: String, function: String): String = SourceContract.firstStatement(SourceContract.functionBody(src, function))
    private fun code(src: String): String = SourceContract.code(src)
    private fun flat(src: String): String = code(src).replace(Regex("\\s+"), " ")

    // ---- the pieces (what the other screens call) -------------------------------------------------------------------------

    @Test fun thePiecesTakeTheParametersOfTheBrief() {
        assertEquals(listOf("modifier: Modifier = Modifier", "content: @Composable ColumnScope.() -> Unit"), SourceContract.parameters(sheet("CosmoSheetSurface"), "CosmoSheetSurface"))
        assertEquals(
            listOf("icon: ImageVector", "label: String", "value: String? = null", "tint: Color? = null", "enabled: Boolean = true", "menu: Boolean = false", "onClick: () -> Unit"),
            SourceContract.parameters(sheet("CosmoActionRow"), "CosmoActionRow"),
        )
        // the brief's four, then two more, optional: an artist's cover is round; an explicit song shows its "E" (P4-19)
        assertEquals(
            listOf("title: String", "subtitle: String?", "imageUrl: String?", "modifier: Modifier = Modifier", "circle: Boolean = false", "explicit: Boolean = false"),
            SourceContract.parameters(sheet("CosmoSheetHeader"), "CosmoSheetHeader"),
        )
        assertEquals(listOf("text: String"), SourceContract.parameters(sheet("CosmoSheetHeader"), "CosmoSheetHeading"))
        assertEquals(listOf("menu: Boolean"), SourceContract.parameters(sheet("CosmoActionRow"), "CosmoSheetGap"))
        assertEquals(listOf("message: String", "modifier: Modifier = Modifier"), SourceContract.parameters(sheet("CosmoToast"), "CosmoToast"))
        assertEquals(
            listOf("onDismiss: () -> Unit", "maxWidth: Dp", "hazeState: HazeState? = null", "content: @Composable ColumnScope.() -> Unit"),
            SourceContract.parameters(sheet("CosmoDialogCard"), "CosmoDialogCard"),
        )
        assertEquals(emptyList(), SourceContract.parameters(sheet("CosmoMenuSurface"), "cosmoMenuSurface"))
        assertTrue(Regex("""fun\s+Modifier\.cosmoMenuSurface\(\)""").containsMatchIn(code(sheet("CosmoMenuSurface"))), "cosmoMenuSurface is a Modifier extension")
        assertEquals(emptyList(), SourceContract.parameters(sheet("CosmoSheetPalette"), "cosmoSheetPalette"))
        assertTrue(Regex("""fun\s+cosmoSheetPalette\(\)\s*:\s*ArtworkPalette""").containsMatchIn(code(sheet("CosmoSheetPalette"))), "the palette is an ArtworkPalette")
    }

    @Test fun thePickRowTakesWhatTheDelegateOfThePickerPasses() {
        assertEquals(
            listOf("title: String", "subtitle: String?", "imageUrl: String?", "selected: Boolean", "onClick: () -> Unit", "modifier: Modifier = Modifier"),
            SourceContract.parameters(sheet("CosmoActionRow"), "CosmoPickRow"),
        )
    }

    @Test fun theSheetPaletteIsTheCoverPaletteInTheOldPalettesShape() {
        val src = flat(sheet("CosmoSheetPalette"))
        assertTrue("ArtworkPalette(" in src, "an ArtworkPalette")
        for (field in listOf(
            "background = glass", "wash = glass", "elevated = Color(palette.surfaceVariant)", "accent = Color(palette.accent)",
            "onBackground = ink", "onBackgroundVariant = Color(palette.textSecondary)", "divider = ink.copy(alpha = SHEET_DIVIDER_ALPHA)",
        )) assertTrue(field in src, "the palette has $field")
        assertTrue("val glass = Color(palette.surfaceHigh)" in src && "val ink = Color(palette.textPrimary)" in src, "the glass is surfaceHigh and the ink textPrimary")
        assertTrue("SHEET_DIVIDER_ALPHA = 0.10f" in src, "the divider is the ink at 10 %")
        assertTrue("remember(palette)" in src, "it changes once per cover")
    }

    // ---- the delegates in the song sheet ----------------------------------------------------------------------------------

    @Test fun everySongSheetDelegateIsTheFirstStatementOfItsFunctionAndNamesWhatItUses() {
        val expected = mapOf(
            "TintedSheet" to ("CosmoSheetSurface" to listOf("modifier", "content")),
            "ActionRow" to ("CosmoActionRow" to listOf("icon", "label", "value", "tint", "enabled", "onClick")),
            "SheetTrackHeader" to ("CosmoSheetHeader" to emptyList()),
            "SheetHeading" to ("CosmoSheetHeading" to listOf("text")),
            "GroupSeparator" to ("CosmoSheetGap" to emptyList()),
        )
        for ((function, target) in expected) {
            val statement = firstStatement(songSheet, function)
            assertTrue(
                statement.startsWith("$look return com.cosmotune.ui.sheets.${target.first}("),
                "the first statement of $function is the delegate to ${target.first}, but it is: $statement",
            )
            SourceContract.assertPassesEveryParameter(statement, target.second)
            // the old body follows the delegate: the look off runs it unchanged
            assertTrue(SourceContract.functionBody(songSheet, function).length > statement.length + 20, "$function keeps its old body")
        }
    }

    @Test fun theDelegatesOfTheSongSheetPassTheParametersTheOldFunctionsHave() {
        val rowNames = SourceContract.names(SourceContract.parameters(songSheet, "ActionRow"))
        assertEquals(listOf("icon", "label", "value", "tint", "accent", "enabled", "onClick"), rowNames, "ActionRow's parameter list is the old one")
        // `accent` is the old look's: the one parameter the delegate leaves behind
        val row = firstStatement(songSheet, "ActionRow")
        assertFalse(Regex("""\baccent\b""").containsMatchIn(row), "the row's accent is the old look's")
        val headerNames = SourceContract.names(SourceContract.parameters(songSheet, "SheetTrackHeader"))
        assertEquals(listOf("song", "modifier", "subtitleColor"), headerNames)
        val header = firstStatement(songSheet, "SheetTrackHeader")
        for (part in listOf("title = song.title", "subtitle = song.artist", "imageUrl = song.thumbnailUrl", "modifier = modifier")) assertTrue(part in header, "the header passes $part")
        assertFalse(Regex("""\bsubtitleColor\b""").containsMatchIn(header), "the subtitle colour is the old look's")
        val sheetNames = SourceContract.names(SourceContract.parameters(songSheet, "TintedSheet"))
        assertEquals(listOf("palette", "imageUrl", "modifier", "content"), sheetNames, "TintedSheet's parameter list is the old one")
    }

    @Test fun theMenuStyleIsPassedThroughToTheRowsAndTheGap() {
        assertTrue(menuStyle in firstStatement(songSheet, "ActionRow"), "the row gets the menu style the held-row menu set")
        assertTrue(menuStyle in firstStatement(songSheet, "GroupSeparator"), "the gap gets it too")
        // the style is still set where it was, so the delegate reads what it always read
        val code = flat(songSheet)
        assertTrue("LocalActionRowStyle provides ActionRowStyle.Menu" in code, "the menu branch sets the style")
        assertTrue("internal enum class ActionRowStyle { Sheet, Menu }" in code, "the enum is the old one")
    }

    @Test fun theSongSheetTakesTheCoverPaletteWhileTheLookIsOnAndKeepsTheMenuBranch() {
        val code = flat(songSheet)
        val chain = Regex("""val palette = if \(menu\) \{ neutralMenuPalette\(\) \} else if \(com\.cosmotune\.ui\.theme\.LocalCosmoShell\.current != null\) \{ com\.cosmotune\.ui\.sheets\.cosmoSheetPalette\(\) \} else \{ rememberArtworkPalette\(song\.thumbnailUrl, artPx = ROW_ART_PX\) \}""")
        assertTrue(chain.containsMatchIn(code), "the palette is the menu's, then the cover's while the look is on, then the track's own")
    }

    // ---- the other phone sites --------------------------------------------------------------------------------------------

    @Test fun theAlbumSheetHeaderIsTheFirstStatementOfItsFunctionAndPassesWhatItShows() {
        val statement = firstStatement(browseSheet, "BrowseSheetHeader")
        assertTrue(statement.startsWith("$look return com.cosmotune.ui.sheets.CosmoSheetHeader("), "the delegate, but it is: $statement")
        for (part in listOf("title = target.title", "imageUrl = target.thumbnailUrl")) assertTrue(part in statement, "the header passes $part")
        // the old fallback of a card without a subtitle, and the round picture of an artist, are kept
        assertTrue("subtitle = target.subtitle.ifBlank { target.type.localizedNoun()" in statement, "the subtitle falls back to the kind of thing it is")
        assertTrue("circle = target.type == BrowseType.ARTIST" in statement, "an artist's cover is round")
        assertEquals(listOf("target: BrowseTarget"), SourceContract.parameters(browseSheet, "BrowseSheetHeader"))
        // the browse sheet itself has no palette of its own, so there is nothing to choose there
        assertFalse("rememberArtworkPalette" in code(browseSheet), "the album sheet reads no artwork palette")
    }

    @Test fun theToastReplacesTheSurfaceOnlyWhileTheLookIsOnAndTheHostIsUntouched() {
        val code = flat(notice)
        assertEquals(1, Regex(Regex.escape("$look com.cosmotune.ui.sheets.CosmoToast(current.message) else Surface(")).findAll(code).count(), "one condition in front of the Surface")
        assertTrue("AnimatedContent(" in code && "if (reduceAnimation || newLook)" in code && "EnterTransition.None togetherWith ExitTransition.None" in code, "the host keeps its reduce-animation rule, and does not slide the new toast a second time")
        assertTrue(code.indexOf("val newLook = com.cosmotune.ui.theme.LocalCosmoShell.current != null") in 0 until code.indexOf("AnimatedContent("), "the look is read once, in composition, before the transition lambda")
        assertTrue("liveRegion = LiveRegionMode.Polite" in code, "the old notice keeps its live region")
        assertTrue("contentKey = { it?.id }" in code, "a repeated action still restarts the notice")
        val toast = flat(sheet("CosmoToast"))
        assertTrue("liveRegion = LiveRegionMode.Polite" in toast, "the new toast is read out politely too")
    }

    @Test fun thePlaylistPickerRowDelegateIsTheFirstStatementAndPassesEveryParameter() {
        val statement = firstStatement(picker, "PlaylistRow")
        assertTrue(statement.startsWith("$look return com.cosmotune.ui.sheets.CosmoPickRow("), "the delegate, but it is: $statement")
        val names = SourceContract.names(SourceContract.parameters(picker, "PlaylistRow"))
        assertEquals(listOf("playlist", "selected", "onClick"), names)
        SourceContract.assertPassesEveryParameter(statement, listOf("selected", "onClick"))
        for (part in listOf("title = playlist.title", "subtitle = playlist.subtitle", "imageUrl = playlist.thumbnailUrl")) assertTrue(part in statement, "the row passes $part")
        val taken = SourceContract.names(SourceContract.parameters(sheet("CosmoActionRow"), "CosmoPickRow"))
        assertTrue(taken.containsAll(listOf("title", "subtitle", "imageUrl", "selected", "onClick")), "CosmoPickRow takes what is passed")
        val pick = flat(sheet("CosmoActionRow"))
        assertTrue("CosmoListRow(" in pick && "CosmoRowLeading.Cover(imageUrl)" in pick, "the pick row is a list row with the playlist's cover")
        assertTrue("private val DISC = 24.dp" in pick, "a 24 dp disc")
    }

    @Test fun theHeldRowMenuTakesTheMenuSurfaceWithOneCondition() {
        val code = flat(held)
        val site = ".pointerInput(Unit) {} .then($look Modifier.cosmoMenuSurface() else Modifier.clip(menuShape).background(plate).border(0.5.dp, hairline, menuShape))"
        assertTrue(site in code, "the menu's container takes cosmoMenuSurface while the look is on and keeps the old plate otherwise")
        assertTrue("import com.cosmotune.ui.sheets.cosmoMenuSurface" in held.replace("\r\n", "\n"), "the extension is imported")
    }

    // ---- the desktop sites ------------------------------------------------------------------------------------------------

    @Test fun theDialogPanelTakesTheGlassCardAfterThePageBranchAndKeepsTheInnerRule() {
        val body = flat(SourceContract.functionBody(surfaces, "DesktopDialogPanel"))
        val page = body.indexOf("if (!popup && LocalDesktopPanelIsPage.current) {")
        val card = body.indexOf("$look { com.cosmotune.ui.sheets.CosmoDialogCard(")
        val old = body.indexOf(".background(DesktopScrim)")
        assertTrue(page in 0 until card && card < old, "order: the page branch, then the glass card, then the old card")
        val statement = body.substring(card, old)
        SourceContract.assertPassesEveryParameter(statement, listOf("onDismiss"))
        assertTrue("maxWidth = maxWidth.dp" in statement, "the old width")
        assertTrue("hazeState = LocalDesktopHaze.current" in statement, "the window's backdrop")
        assertTrue("CompositionLocalProvider(LocalDesktopPanelIsPage provides false) { content() }" in statement, "anything it opens in turn is a card")
        assertTrue("return }" in statement, "the old card is not drawn as well")
        assertEquals(listOf("onDismiss: () -> Unit", "maxWidth: Int", "popup: Boolean = false", "content: @Composable ColumnScope.() -> Unit"), SourceContract.parameters(surfaces, "DesktopDialogPanel"))
    }

    @Test fun thePlaylistDialogFrameTakesTheSameCardAsItsFirstStatement() {
        val statement = firstStatement(playlistUi, "DesktopDialogFrame")
        assertTrue(statement.startsWith("$look { com.cosmotune.ui.sheets.CosmoDialogCard("), "the first statement is the card, but it is: $statement")
        SourceContract.assertPassesEveryParameter(statement, listOf("onDismiss"))
        for (part in listOf("maxWidth = 480.dp", "hazeState = LocalDesktopHaze.current", "{ content() }", "return }")) assertTrue(part in statement, "the frame has $part")
    }

    @Test fun theDesktopSongMenuTakesTheMenuSurfaceAndTheHazeIsVisibleToTheDialogFiles() {
        val code = flat(songMenu)
        assertTrue(
            ".widthIn(min = 232.dp, max = 300.dp) .then($look Modifier.cosmoMenuSurface() else Modifier.clip(shape).desktopCard(shape).padding(vertical = 6.dp))" in code,
            "the menu's card takes cosmoMenuSurface while the look is on",
        )
        assertTrue(".heightIn(max = 460.dp) .verticalScroll(rememberScrollState())" in code, "the menu still scrolls under its cap")
        assertTrue("import com.cosmotune.ui.sheets.cosmoMenuSurface" in songMenu.replace("\r\n", "\n"), "the extension is imported")
        assertTrue("internal val LocalDesktopHaze = staticCompositionLocalOf<HazeState?> { null }" in flat(desktopApp), "the backdrop is visible to the dialog files")
    }

    // ---- E7, the draw phase, the colours ---------------------------------------------------------------------------------

    @Test fun theSheetTheMenuAndTheToastNeverTakeAHazeStateAndOnlyTheDialogPassesOneThrough() {
        for (name in listOf("CosmoSheetSurface", "CosmoMenuSurface", "CosmoToast")) {
            val src = code(sheet(name))
            assertFalse("HazeState" in src, "$name takes no HazeState (E7)")
            assertTrue("cosmoGlass(hazeState = null" in src, "$name draws the solid glass")
        }
        val dialog = code(sheet("CosmoDialogCard"))
        assertTrue("hazeState: HazeState? = null" in dialog && "cosmoGlass(hazeState = hazeState" in dialog, "the dialog passes the caller's backdrop (the desktop's) to the glass")
        // the desktop's dialogs are the only callers, and they pass the window's backdrop
        for (src in listOf(surfaces, playlistUi)) assertTrue("hazeState = LocalDesktopHaze.current" in flat(src))
        assertFalse(Regex("""\bHazeState\b""").containsMatchIn(code(songSheet)), "no phone sheet takes a HazeState")
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer)\s*(\([^)]*\))?\s*$""")

    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        var seen = 0
        for (name in files) {
            val src = code(sheet(name))
            var from = 0
            while (true) {
                val at = src.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (src[at - 1].isLetterOrDigit() || src[at - 1] == '_')) continue
                seen++
                assertTrue(enclosingHeads(src, at).any { drawHead.containsMatchIn(it) }, "$name: live( at line ${src.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
        assertTrue(seen >= 3, "the state layer, the disc and the focus ring read the live palette ($seen)")
    }

    @Test fun noBrushOrLiveColourIsRememberedAndNoColourIsPickedByHand() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient|Fill\()""")
        for (name in files) {
            val src = code(sheet(name))
            assertTrue(remembering.find(src) == null, "$name remembers a brush or a live colour")
            assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White)\b""").containsMatchIn(src), "$name uses a named colour")
            // the one documented exception: the black behind a dialog
            if (name != "CosmoDialogCard") assertFalse(Regex("""Color\.Black\b""").containsMatchIn(src), "$name uses black")
        }
        assertEquals(1, Regex("""Color\.Black""").findAll(code(sheet("CosmoDialogCard"))).count(), "black only as the scrim")
    }

    // ---- the numbers of the spec -----------------------------------------------------------------------------------------

    @Test fun theSheetIsDetachedAndTheSpecsNumbersAreTheOnesInTheSources() {
        val surface = flat(sheet("CosmoSheetSurface"))
        assertTrue("SuperellipseShape(CosmoRadii.sheet)" in surface, "36 dp corners on every corner")
        assertTrue("start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset" in surface, "8 dp from the sides and from the bottom")
        assertTrue("SHEET_MAX_HEIGHT_SHARE = 0.85f" in surface && "heightIn(max = windowHeight * SHEET_MAX_HEIGHT_SHARE)" in surface, "at most 85 % of the window")
        assertTrue("CosmoSheetHandle(" in surface && "verticalScroll(" in surface && "weight(1f, fill = false)" in surface, "the grabber stays, the rows scroll below it")
        assertTrue(surface.indexOf("CosmoSheetHandle(") < surface.indexOf("verticalScroll("), "the grabber is above the rows")
    }

    @Test fun theRowsHaveTheNumbersOfTheSheetAndTheMenu() {
        val row = flat(sheet("CosmoActionRow"))
        for (number in listOf(
            "SHEET_ROW_HEIGHT = 54.dp", "SHEET_ROW_RADIUS = 18.dp", "SHEET_ROW_INSET = 8.dp", "SHEET_ROW_PADDING = 14.dp", "SHEET_ROW_GAP = 16.dp", "SHEET_ICON = 24.dp",
            "SHEET_LABEL_SP = 15.5f", "MENU_ROW_HEIGHT = 44.dp", "MENU_ROW_RADIUS = 14.dp", "MENU_ROW_PADDING = 12.dp", "MENU_ROW_GAP = 12.dp", "MENU_ICON = 20.dp",
            "MENU_ICON_ALPHA = 0.85f", "MENU_LABEL_SP = 14.5f", "LABEL_WEIGHT = 500", "VALUE_SP = 13f", "DISABLED_ALPHA = 0.38f", "STATE_LAYER_ALPHA = 0.12f",
            "SHEET_GAP = 6.dp", "MENU_RULE_HEIGHT = 1.dp", "MENU_RULE_MARGIN_V = 4.dp", "MENU_RULE_MARGIN_H = 8.dp",
        )) assertTrue(number in row, "the row has $number")
        assertTrue("Color(palette.textSecondary)" in row && "Color(palette.textTertiary)" in row && "Color(palette.textPrimary)" in row, "icon, value and label colours")
        assertTrue("role = Role.Button" in row && "contentDescription = null" in row, "one button for assistive technology, the icon decorative")
        assertTrue("cosmoFocusRing(interaction, radius, floating = true)" in row, "a visible focus ring")
        assertTrue("collectIsPressedAsState" in row && "collectIsHoveredAsState" in row, "press and hover show the state layer")
        assertTrue("enabled = enabled" in row, "a disabled row does not answer a tap")
    }

    @Test fun theHeaderTheHeadingTheToastTheMenuAndTheDialogHaveTheNumbersOfTheSpec() {
        val header = flat(sheet("CosmoSheetHeader"))
        for (number in listOf(
            "HEAD_PADDING_H = 20.dp", "HEAD_PADDING_BOTTOM = 16.dp", "HEAD_GAP = 14.dp", "HEAD_COVER = 56.dp", "HEAD_COVER_RADIUS = 14.dp", "HEAD_TITLE_SP = 17f", "HEAD_SUBTITLE_SP = 13.5f",
            "HEADING_PADDING_H = 22.dp", "HEADING_PADDING_TOP = 16.dp", "HEADING_PADDING_BOTTOM = 4.dp", "HEADING_SP = 12f",
        )) assertTrue(number in header, "the header has $number")
        assertTrue("CoverImage(imageUrl, HEAD_COVER, HEAD_COVER_RADIUS)" in header, "a 56 dp cover of radius 14")
        assertTrue("heading()" in header, "the overline is a heading")
        val toast = flat(sheet("CosmoToast"))
        for (number in listOf(
            "TOAST_MIN_HEIGHT = 52.dp", "TOAST_PADDING_START = 18.dp", "TOAST_PADDING_OTHER = 8.dp", "TOAST_SP = 14.5f", "TOAST_WEIGHT = 500", "TOAST_RISE = 16.dp", "TOAST_LIFT = 8.dp",
            "RoundedCornerShape(percent = 50)",
        )) assertTrue(number in toast, "the toast has $number")
        assertTrue("CosmoMotion.move(reduced)" in toast, "it rises on the move spring")
        assertTrue("CosmoSpacing.margin" in toast, "16 dp from the sides")
        val menu = flat(sheet("CosmoMenuSurface"))
        assertTrue("SuperellipseShape(CosmoRadii.lg)" in menu && "MENU_PADDING = 6.dp" in menu, "radius 22, padding 6")
        val dialog = flat(sheet("CosmoDialogCard"))
        assertTrue("Color.Black.copy(alpha = 0.40f)" in dialog && "SuperellipseShape(CosmoRadii.sheet)" in dialog, "a 40 % scrim and 36 dp corners")
        assertTrue("onClick = onDismiss" in dialog && "onClick = {}" in dialog, "the scrim closes the dialog, the card swallows its own clicks")
        assertTrue(dialog.indexOf("onClick = {}") > dialog.indexOf("onClick = onDismiss"), "the swallowing layer is in the card, behind its content")
    }

    // ---- the fork table ---------------------------------------------------------------------------------------------------

    @Test fun theForkTableHasARowForEveryUpstreamFileThisTaskChangedAndEveryRowHasFourColumns() {
        val rows = SourceContract.read("FORK.md").lines().filter { it.startsWith("|") }
        for (row in rows) {
            val columns = row.trim().removeSurrounding("|").split(Regex("""(?<!\\)\|"""))
            assertEquals(4, columns.size, "four columns: ${row.take(60)}")
        }
        for (file in listOf(
            "ui/components/SongActionsSheet.kt", "ui/components/BrowseActionsSheet.kt", "ui/components/QueueActionNotice.kt", "ui/components/PlaylistPickerSheet.kt",
            "ui/components/HeldContextMenu.kt", "desktop/DesktopSurfaces.kt", "desktop/DesktopPlaylistUi.kt", "desktop/DesktopSongMenuUi.kt", "desktop/DesktopApp.kt",
        )) {
            assertTrue(rows.any { it.contains(file) && it.contains("(P4-14)") }, "a P4-14 row for $file")
        }
    }
}
