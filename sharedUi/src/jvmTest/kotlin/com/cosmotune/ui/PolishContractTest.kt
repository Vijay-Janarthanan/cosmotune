package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The polish pass of the new look (task P4-19), pinned by reading the sources (`SourceContract`): what cannot be seen without a window.
 * One section per item of the pass.
 */
class PolishContractTest {
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
        assertTrue("drawRect(tileFill(colors))" in tile && "tileEdge(shape.createOutline(size, layoutDirection, this), colors)" in tile, "the fill, then the rim and the hairline")
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
        assertTrue("tileEdge(TILE_SHAPE.createOutline(size, layoutDirection, this), colors)" in art, "the empty state's back tiles keep a tile's edge")
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
            "if (light) drawOutline(outline, colors.live(CoverField.TEXT_PRIMARY).copy(alpha = TileDepth.hairlineAlpha(dark = false)), style = ring)" in cover,
            "a pale cover keeps an edge on a pale page",
        )
    }
}
