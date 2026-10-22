package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The polish pass of the new look (task P4-19, visual), pinned by reading the sources (`SourceContract`): what cannot be seen without a window.
 * One section per item of the pass.
 */
class VisualPolishContractTest {
    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    private fun code(path: String) = SourceContract.code(SourceContract.read(path))

    // ---- the colour intensity reaches Now Playing's own palette --------------------------------------------------------------------

    @Test fun nowPlayingsOwnPaletteLoadsAtTheIntensityOfTheSetting() {
        val src = SourceContract.read("$ui/nowplaying/NowPlayingColors.kt")
        val body = SourceContract.functionBody(src, "rememberLightThemeColors")
        assertTrue("shell.settings.intensity" in body && "Intensity.multiplier(" in body, "it reads the setting")
        assertTrue(Regex("""produceState\(DarkLookup\(false, null\),[^{]*\bintensity\)""").containsMatchIn(body), "the dark palette is looked up again when the setting changes")
        assertTrue(Regex("""CoverPaletteLoader\.load\([^)]*\bintensity = intensity\)""").containsMatchIn(body), "and loaded at it")
    }

    @Test fun theLoaderBuildsTheSpecOfTheSetting() {
        val body = SourceContract.code(SourceContract.functionBody(SourceContract.read("$ui/theme/CoverPaletteLoader.kt"), "load"))
        assertTrue("CoverPaletteSpec.forIntensity(intensity)" in body, "the ceilings move with the setting, not only the budget")
        assertTrue("CoverPaletteSpec(intensity = intensity)" !in body)
    }

    // ---- tiles and cards lift off the page (`--e1`, `--hl`; TileDepth) -------------------------------------------------------------

    @Test fun everyTileIsLiftedAndEdgedLikeTheBoards() {
        val tiles = SourceContract.read("$ui/blocks/CosmoTiles.kt")
        val tile = SourceContract.functionBody(tiles, "cosmoTile")
        assertTrue(Regex("""tileLift\(shape, colors\)\s*\.clip\(shape\)""").containsMatchIn(tile), "the lift comes first, outside the clip")
        assertTrue("drawRect(tileFill(colors))" in tile && "tileEdge(shape, colors)" in tile, "the fill, then the rim and the hairline")
        assertTrue(tile.indexOf("drawRect(tileFill(colors))") < tile.indexOf("edge()"), "the edge is drawn over the fill")
        val mix = SourceContract.functionBody(tiles, "CosmoMixTile")
        assertTrue(mix.indexOf(".tileLift(shape, colors)") in 0 until mix.indexOf(".clip(shape)"), "the mix tile is lifted outside its clip")
        assertTrue("edge()" in mix && mix.indexOf("ambientWash(") < mix.indexOf("edge()"), "and edged over its ground")
        val liked = SourceContract.functionBody(tiles, "CosmoLikedTile")
        assertTrue("lift = lift" in liked && "tileLiftOf(SuperellipseShape(BlockMetrics.TILE_RADIUS_DP.dp), colors)" in liked, "the liked tile is lifted")
        assertTrue("ControlMetrics.highlightPrimary(colors.dark)" in liked, "under the button's own top highlight (`--btn-shadow`)")
        val mood = SourceContract.functionBody(SourceContract.read("$ui/blocks/CosmoMood.kt"), "CosmoMoodTile")
        assertTrue("lift = lift" in mood && "tileLiftOf(SuperellipseShape(BlockMetrics.MOOD_TILE_RADIUS_DP.dp), colors)" in mood, "the mood tile is lifted")
        assertTrue("tileEdge(outline, colors)" in mood, "and edged along its morphing outline")
        val art = SourceContract.functionBody(SourceContract.read("$ui/states/CosmoEmptyState.kt"), "EmptyArt")
        assertTrue("tileEdge(TILE_SHAPE, colors)" in art, "the empty state's back tiles keep a tile's edge")
        // the sign-in banner, the New playlist cell, the About and latest-release tiles and the search cards are the tile fill
        for ((file, call) in listOf(
            "states/CosmoSignInBanner.kt" to ".cosmoTile(SuperellipseShape(BANNER_RADIUS))",
            "library/CosmoLibraryGrid.kt" to ".cosmoTile(shape)",
            "pages/PageParts.kt" to ".cosmoTile(shape)",
            "pages/CosmoArtistPage.kt" to ".cosmoTile(shape)",
            "search/CosmoSearchResults.kt" to ".cosmoTile(shape)",
        )) assertTrue(call in code("$ui/$file"), "$file draws its tile with cosmoTile")
    }

    @Test fun theLiftAndTheEdgeReadThePaletteWhileDrawingWithTheNumbersOfTileDepth() {
        val surface = code("$ui/components/TileSurface.kt")
        assertEquals(2, Regex("""spot = \{ colors\.live\(CoverField\.SHADOW\)\.copy\(alpha = 1f\) \}""").findAll(surface).count(), "the key shadow is the palette's, read in the layer block")
        assertTrue("TileDepth.ELEVATION_DP.dp" in surface && "TileDepth.AMBIENT_ALPHA" in surface, "the lift of `--e1`")
        for (number in listOf("TileDepth.rimAlpha(colors.dark)", "TileDepth.hairlineAlpha(colors.dark)", "TileDepth.RIM_DP", "TileDepth.HAIRLINE_DP")) {
            assertTrue(number in surface, "the edge uses $number")
        }
        assertTrue("keepRedrawnWith(colors.palette)" in surface, "the cached rim is rebuilt when the theme flips")
        assertFalse("dropShadow" in surface, "an elevation shadow, not a dropShadow that leaves a rectangle on a light page")
        val morph = code("$ui/components/MorphBox.kt")
        assertTrue("lift: Lift? = null" in morph && "if (lift != null) applyLift(lift)" in morph, "a MorphBox can carry a lift")
        assertTrue(morph.indexOf("applyLift(lift)") > morph.indexOf("this.scaleX = scaleX.value"), "in the layer that scales with the press")
        assertTrue("spotShadowColor = lift.spot()" in code("$ui/components/Shadows.kt"), "read in the layer block")
        val cover = code("$ui/components/CoverImage.kt")
        assertTrue(
            "if (light) ring.draw(this, colors.live(CoverField.TEXT_PRIMARY).copy(alpha = TileDepth.hairlineAlpha(dark = false)))" in cover,
            "a pale cover keeps an edge on a pale page",
        )
    }

    // ---- the small controls: a quiet chip, a tinted icon button ----------------------------------------------------------------

    @Test fun aChipCanStayQuietAndTheSearchFiltersDoOnATapOfTheChosenOne() {
        val chip = SourceContract.read("$ui/components/CosmoChip.kt")
        assertEquals("haptic: Boolean = true", SourceContract.parameters(chip, "CosmoChip").last(), "one more optional parameter, at the end")
        assertTrue("onClick = { if (haptic) haptics.play(Haptic.Select); onClick() }" in SourceContract.code(chip), "the select haptic only when asked")
        assertTrue("haptic = entry != filter" in code("$ui/search/CosmoSearch.kt"), "a filter chip buzzes only when the filter changes")
        for (file in listOf("search/CosmoBrowseBlocks.kt", "settings/LookPreview.kt")) assertFalse("haptic =" in code("$ui/$file"), "$file keeps the haptic")
    }

    @Test fun anIconButtonTakesARestingTintAndNowPlayingsToolsRestInSecondaryInk() {
        val button = SourceContract.read("$ui/components/CosmoIconButton.kt")
        assertEquals("tint: Color? = null", SourceContract.parameters(button, "CosmoIconButton").last(), "one more optional parameter, at the end")
        assertTrue("val ink = if (!isOn && tint != null) tint else Color(" in SourceContract.code(button), "the tint is the resting ink; an on state keeps its own")
        val tools = SourceContract.functionBody(SourceContract.read("$ui/nowplaying/NowPlayingControls.kt"), "Tools")
        assertTrue("val resting = Color(LocalCosmoColors.current.palette.textSecondary)" in tools, "secondary ink, read in composition")
        // Y-26 (Video mode): a fifth, "Watch the music video", drawn only when Video can play the song's music video.
        assertEquals(5, Regex("""tint = resting""").findAll(tools).count(), "lyrics, output, Listen Together, the music video and the queue")
        assertFalse(".live(" in tools, "a composition colour, not a live one")
    }

    // ---- the album, playlist and add-to-playlist sheets on the song sheet's surface ------------------------------------------------

    private val mainActivity = "app/src/main/java/com/cosmotune/music/MainActivity.kt"

    @Test fun theSurfaceSheetIsASwitchOfTheBridgesSheet() {
        val src = SourceContract.read("$ui/sheets/SurfaceSheetSwitch.kt")
        val bridge = SourceContract.read("$ui/bridge/SheetBridge.kt")
        assertEquals(SourceContract.parameters(bridge, "ModalBottomSheet"), SourceContract.parameters(src, "SurfaceSheetSwitch"), "the same parameters, names, types, defaults and order")
        val body = SourceContract.functionBody(src, "SurfaceSheetSwitch")
        val first = SourceContract.firstStatement(body)
        assertTrue(first.startsWith("if (LocalCosmoShell.current == null) { com.cosmotune.ui.bridge.ModalBottomSheet("), "the look off is the old call: $first")
        SourceContract.assertPassesEveryParameter(first, SourceContract.names(SourceContract.parameters(src, "SurfaceSheetSwitch")))
        val on = body.substringAfter(first)
        for (line in listOf(
            "androidx.compose.material3.ModalBottomSheet(", "shape = RectangleShape", "containerColor = Color.Transparent", "tonalElevation = 0.dp",
            "scrimColor = CosmoSheetScrim", "dragHandle = null", "contentWindowInsets = contentWindowInsets", "CosmoSheetSurface(content = content)",
        )) assertTrue(line in on, "the look on: $line")
        val code = SourceContract.code(src)
        assertTrue("val CosmoSheetScrim: Color = Color.Black.copy(alpha = 0.40f)" in code, "the spec's 40 % black")
        assertTrue("if (LocalCosmoShell.current != null) CosmoSheetScrim else BottomSheetDefaults.ScrimColor" in code, "Material's default while the look is off")
    }

    @Test fun theTwoHostsCallTheSwitchAndTheSongSheetTakesTheSpecsScrim() {
        val main = SourceContract.code(SourceContract.read(mainActivity))
        assertEquals(2, Regex("""com\.cosmotune\.ui\.sheets\.SurfaceSheetSwitch\(""").findAll(main).count(), "the picker's and the browse sheet's hosts")
        assertTrue(Regex("""SurfaceSheetSwitch\(\s*onDismissRequest = dismiss,\s*containerColor = MaterialTheme\.colorScheme\.background,\s*\) \{\s*PlaylistPickerSheet\(""").containsMatchIn(main), "the picker's host, its arguments unchanged")
        assertTrue(Regex("""SurfaceSheetSwitch\(\s*onDismissRequest = \{ browseActions = null \},\s*containerColor = MaterialTheme\.colorScheme\.background,\s*\) \{\s*browseMenuBody\(target, SongActionsPresentation\.Sheet\)""").containsMatchIn(main), "the browse sheet's host")
        assertTrue(Regex("""containerColor = Color\.Transparent,\s*dragHandle = null,\s*scrimColor = com\.cosmotune\.ui\.sheets\.cosmoSheetScrim\(\),\s*\) \{\s*songMenuBody\(song, SongActionsPresentation\.Sheet\)""").containsMatchIn(main), "the song sheet dims the page with 40 %")
        val fork = SourceContract.read("FORK.md")
        assertTrue(fork.lines().any { it.startsWith("| `app/src/main/java/com/cosmotune/music/MainActivity.kt`") && "SurfaceSheetSwitch" in it && "cosmoSheetScrim()" in it && "(P4-19)" in it }, "a FORK row for the three hosts")
    }

    @Test fun theSheetHeaderShowsTheExplicitMarkAgain() {
        val header = SourceContract.functionBody(SourceContract.read("$ui/sheets/CosmoSheetHeader.kt"), "CosmoSheetHeader")
        assertTrue("if (explicit) ExplicitMark(Color(palette.textPrimary))" in header, "the mark in front of the title, in its ink")
        val mark = SourceContract.code(SourceContract.read("$ui/sheets/CosmoSheetHeader.kt"))
        assertTrue("MARK_BORDER_ALPHA = 0.72f" in mark && "RoundedCornerShape(2.dp)" in mark && "text = \"E\"" in mark, "the catalogue's outlined E, as ExplicitSongTitle draws it")
        val songSheet = SourceContract.read("app/src/main/java/com/cosmotune/music/ui/components/SongActionsSheet.kt")
        val delegate = SourceContract.firstStatement(SourceContract.functionBody(songSheet, "SheetTrackHeader"))
        assertTrue(delegate.endsWith("explicit = song.isExplicit == true)"), "the song sheet's delegate passes it: $delegate")
        assertTrue(SourceContract.read("FORK.md").lines().any { it.startsWith("| `app/src/main/java/com/cosmotune/music/ui/components/SongActionsSheet.kt`") && "explicit = song.isExplicit == true" in it }, "with its FORK row")
    }

    @Test fun everyRowOfTheForkTableHasFourColumns() {
        for (line in SourceContract.read("FORK.md").lines().filter { it.startsWith("|") }) {
            var inside = false
            var pipes = 0
            for (c in line) { if (c == '`') inside = !inside else if (c == '|' && !inside) pipes++ }
            assertEquals(5, pipes, "four columns: ${line.take(100)}")
        }
    }

    // ---- labels that fit a 360 dp phone: Library's four segments, the mood tiles' names ------------------------------------------

    @Test fun aSegmentedControlCanShareItsRowAndLibrarysFourDo() {
        val src = SourceContract.read("$ui/components/CosmoSegmented.kt")
        assertEquals("fill: Boolean = false", SourceContract.parameters(src, "CosmoSegmented").last(), "one more optional parameter: the others are unchanged")
        val control = SourceContract.functionBody(src, "CosmoSegmented")
        assertTrue("(if (fill) modifier.fillMaxWidth() else modifier).selectableGroup()" in control, "a filling control takes the row")
        assertTrue("modifier = if (fill) Modifier.weight(1f) else Modifier" in control, "its segments share it equally")
        assertTrue("ControlMetrics.segmentShowsCheck(options.size, fill)" in control, "the check only where it fits")
        val segment = SourceContract.functionBody(src, "Segment")
        assertTrue("(if (fill) ControlMetrics.SEGMENT_FILL_PADDING_DP else ControlMetrics.SEGMENT_PADDING_DP).dp" in segment, "4 dp inside the ends instead of 16")
        assertTrue("if (selected && check) Icon(CosmoIcons.Check" in segment)
        assertTrue("minimumInteractiveComponentSize()" in segment && ".height(ControlMetrics.SEGMENT_HEIGHT_DP.dp)" in segment, "the 48 dp target stays")
        val code = SourceContract.code(src)
        assertTrue("FitLabel(maxSp = 14f, minSp = ControlMetrics.SEGMENT_LABEL_MIN_SP, stepSp = 0.5f, wholeWords = false)" in code, "the label shrinks to 12 sp, no smaller")
        assertTrue(Regex("""maxLines = 1,\s*overflow = TextOverflow\.Ellipsis,\s*autoSize = FIT_LABEL""").containsMatchIn(code), "on one line, cut past the floor")
        val library = code("$ui/library/CosmoLibrary.kt")
        // P4-17: below the wide page only; a wide page keeps the classic control at the start (the board's `.d2-lib .seg2`)
        assertTrue("fill = !wide," in library && "horizontalScroll(" !in library, "Library's four share the row and no longer scroll sideways")
    }

    @Test fun aMoodTilesNameShrinksToFitOnWholeWords() {
        val src = SourceContract.read("$ui/blocks/CosmoMood.kt")
        val tile = SourceContract.functionBody(src, "CosmoMoodTile")
        assertTrue("maxLines = LabelFit.maxLines(label)" in tile, "one word, one line; several, two lines")
        assertTrue("autoSize = if (big) BIG_LABEL_FIT else LABEL_FIT" in tile && "overflow = TextOverflow.Ellipsis" in tile)
        assertTrue("lineHeight = 1.1.em" in tile, "the line height follows the label when it shrinks")
        val code = SourceContract.code(src)
        assertTrue("FitLabel(20f, BlockMetrics.MOOD_LABEL_MIN_SP, BlockMetrics.MOOD_LABEL_STEP_SP, wholeWords = true)" in code, "20 sp down to 12, on whole words")
        assertTrue("FitLabel(24f, BlockMetrics.MOOD_LABEL_MIN_SP, BlockMetrics.MOOD_LABEL_STEP_SP, wholeWords = true)" in code, "24 sp in a big tile")
        val fit = SourceContract.code(SourceContract.read("$ui/components/FitLabel.kt"))
        assertTrue("LabelFit.sizes(maxSp, minSp, stepSp)" in fit, "the sizes, largest first")
        assertTrue("if (layout.hasVisualOverflow) continue" in fit, "a size fits when nothing overflows (not only when the last line was ellipsised)")
        assertTrue("if (wholeWords && !LabelFit.breaksOnlyAtSpaces(" in fit, "and, for a name, when no word is broken")
    }

    // ---- the sign-in banner signs in from anywhere on it -------------------------------------------------------------------------

    @Test fun theWholeSignInTileSignsInAndKeepsItsButton() {
        val body = SourceContract.functionBody(SourceContract.read("$ui/states/CosmoSignInBanner.kt"), "CosmoSignInBanner")
        val click = ".clickable(interactionSource = interaction, indication = LocalIndication.current, onClickLabel = action, role = Role.Button, onClick = onSignIn)"
        assertTrue(click in body, "the tile is a button with the same action")
        assertTrue(body.indexOf(".cosmoFocusRing(interaction, BANNER_RADIUS)") in 0 until body.indexOf(".cosmoTile(SuperellipseShape(BANNER_RADIUS))"), "a focus ring round the tile")
        assertTrue(body.indexOf(".cosmoTile(SuperellipseShape(BANNER_RADIUS))") < body.indexOf(click) && body.indexOf(click) < body.indexOf(".padding(BANNER_PADDING)"), "the press fills the tile")
        assertTrue("val action = stringResource(Res.string.sign_in)" in body, "the action is read as \"Sign in\"")
        assertTrue("CosmoButton(text = stringResource(Res.string.sign_in), onClick = onSignIn" in body, "the button at the end stays")
    }

    // ---- every skeleton of the new look is the cover's ---------------------------------------------------------------------------

    @Test fun everySkeletonOfTheNewLookDrawsTheCoversSkeletonColours() {
        // the shared skeletons: every block is a ShimmerBox, which hands over to CosmoShimmer (skelBase, skelShine) as its first statement
        val skeletons = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/Skeletons.kt")
        val shimmer = SourceContract.functionBody(skeletons, "ShimmerBox")
        assertTrue(
            SourceContract.firstStatement(shimmer).startsWith("if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.states.CosmoShimmer("),
            "ShimmerBox is the cover's shimmer in the new look",
        )
        val rest = SourceContract.code(skeletons).replace(shimmer, "").lines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
        for (paint in listOf("drawRect", "background(", "drawWithCache", "drawBehind", "MaterialTheme.colorScheme")) {
            assertFalse(paint in rest, "outside ShimmerBox no shared skeleton paints anything of its own: $paint")
        }
        val cosmo = SourceContract.code(SourceContract.read("$ui/states/CosmoShimmer.kt"))
        assertTrue("CoverField.SKEL_BASE" in cosmo && "CoverField.SKEL_SHINE" in cosmo, "the cover's skeleton pair")
        // the new pages draw their own skeleton, at full strength and of the new rows' measure
        assertTrue("pageSkeleton(\"skeleton:release\", rowDp = PageMetrics.TRACK_ROW_DP)" in code("$ui/pages/CosmoReleasePage.kt"))
        assertTrue("pageSkeleton(\"skeleton:artist\", rowDp = PageMetrics.ROW_DP)" in code("$ui/pages/CosmoArtistPage.kt"))
        val parts = SourceContract.read("$ui/pages/PageParts.kt")
        val skeleton = SourceContract.functionBody(parts, "pageSkeleton")
        assertTrue(Regex("""CosmoShimmer\(""").findAll(skeleton).count() >= 5, "every block is the cover's shimmer")
        assertFalse("alpha" in skeleton, "not dimmed over the page")
        assertTrue("BlockMetrics.ROW_COVER_DP.dp" in skeleton && "SuperellipseShape(12.dp)" in SourceContract.code(parts), "a 52 dp cover of radius 12 (`.skel-row`)")
        // and no new screen draws a skeleton of its own in other colours
        val newLook = java.io.File(SourceContract.root, ui).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in newLook) {
            val text = SourceContract.code(file.readText().replace("\r\n", "\n"))
            assertFalse("detailSkeleton(" in text, "${file.name} draws the old page skeleton")
            if ("ShimmerBox(" in text || "CosmoShimmer(" in text) assertFalse("surfaceVariant" in text && "Shimmer" in text && "MaterialTheme" in text, "${file.name} paints a skeleton in Material colours")
        }
    }
}
