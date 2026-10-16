package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The defects the real app showed on an emulator and in the desktop exe (task P4-22), pinned by reading the sources
 * (`SourceContract`): what cannot be seen without a window. One section per defect.
 */
class EmulatorFindingsContractTest {
    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    private fun code(path: String) = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    // ---- the mix tile: a title of two lines no longer runs into the fan of covers -------------------------------------------------

    @Test fun theMixTileGivesTheFanTheRoomTheTextLeavesInsteadOfLayingThemOnTopOfEachOther() {
        val tiles = SourceContract.read("$ui/blocks/CosmoTiles.kt")
        val mix = SourceContract.code(SourceContract.functionBody(tiles, "CosmoMixTile")).replace(Regex("\\s+"), " ")
        assertTrue("Layout( content = { MixFan(covers) Column {" in mix, "the fan and the text are the two children of one layout, the fan first")
        assertTrue("BlockMetrics.mixFanFit(tileHeightDp = height.toDp().value, textHeightDp = text.height.toDp().value)" in mix, "the pure rule decides, from the measured text")
        assertTrue("BlockMetrics.mixTextWidthDp(width.toDp().value)" in mix, "the text is measured at the tile's width less its sides")
        assertTrue("fan.placeRelativeWithLayer(" in mix && "scaleX = fit.scale" in mix && "scaleY = fit.scale" in mix, "the fan is scaled in its layer, not recomposed")
        assertTrue("LayoutDirection.Rtl" in mix, "its corner is at the end side in a right-to-left layout too")
        assertTrue("if (fit.visible)" in mix, "and it is left out when there is no room")
        assertFalse("Alignment.BottomStart" in mix || "Alignment.TopEnd" in mix, "neither block is aligned on its own any more: that is what let them overlap")
        assertTrue("maxLines = 2" in mix, "a title takes up to two lines")
    }

    // ---- the Settings row of the new look is no longer a preview, in every language -----------------------------------------------

    private val resources = java.io.File(SourceContract.root, "sharedUi/src/commonMain/composeResources")

    private fun valueOf(folder: String, key: String): String {
        val text = java.io.File(resources, "$folder/strings.xml").readText()
        val match = Regex("""<string name="$key">([^<]*)</string>""").find(text)
        return (match ?: fail("$folder has no $key")).groupValues[1]
    }

    private val folders get() = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().map { it.name }.sorted()

    @Test fun theNewLookRowSaysNewLookAndTheRedesignedInterfaceInEnglish() {
        assertEquals("New look", valueOf("values", "cosmo_new_look"))
        assertEquals("The redesigned interface. Turn it off to go back to the classic layout.", valueOf("values", "cosmo_new_look_summary"))
    }

    @Test fun noLanguageKeepsTheOldPreviewWordingAndEveryOtherLanguageHasItsOwnText() {
        assertEquals(18, folders.size, "the base file and the 17 translations")
        val english = listOf(valueOf("values", "cosmo_new_look"), valueOf("values", "cosmo_new_look_summary"))
        for (folder in folders) {
            for ((i, key) in listOf("cosmo_new_look", "cosmo_new_look_summary").withIndex()) {
                val value = valueOf(folder, key)
                assertTrue(value.isNotBlank(), "$folder $key is empty")
                assertFalse("preview" in value.lowercase() || "Still being built" in value, "$folder $key still calls the new look a preview: $value")
                if (folder != "values") assertTrue(value != english[i], "$folder $key is still the English text")
            }
        }
    }

    // ---- the desktop playlist page has no album column when no row has an album --------------------------------------------------

    @Test fun theDesktopCollectionPageDecidesTheAlbumColumnOnEveryRowItHasLoadedWithTheSharedRule() {
        val page = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt")).replace(Regex("\\s+"), " ")
        assertTrue("val showAlbum = remember(songs, isAlbum) { CollectionColumns.showsAlbum(isAlbum, songs.map { it.albumName }) }" in page, "the shared rule, over all the rows, not the filtered ones")
        assertTrue("showAlbum = showAlbum," in page, "and the rows get its answer")
        assertFalse("showAlbum = !isAlbum" in page, "the old rule, which drew a dash on every row of a playlist without albums, is gone")
        val row = SourceContract.code(SourceContract.functionBody(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt"), "CollectionTrackRow")).replace(Regex("\\s+"), " ")
        assertTrue("if (showAlbum) { Text(" in row && "Column(Modifier.weight(1f))" in row, "without the column the title and the artist (weight 1) take its room")
    }

    // ---- the desktop page scrolls to the window's edge: the bar's room is content padding, and only while the bar is there --------------

    private val desktopApp = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"

    @Test fun theDesktopFramePadsTheContentForTheBarByTheSharedRuleAndOnlyWithASong() {
        val frame = SourceContract.code(SourceContract.functionBody(SourceContract.read(desktopApp), "DesktopFrame")).replace(Regex("\\s+"), " ")
        assertTrue(
            "LocalCosmoShell.current != null -> com.cosmotune.design.ShellMetrics.desktopPageBottomDp(compact, songShown = LocalNowPlaying.current != null).dp" in frame,
            "the new look pads by the pure rule, with whether a song is selected",
        )
        assertTrue("compact -> 128.dp" in frame && "else -> 16.dp" in frame, "the old look pads as it did")
    }

    @Test fun theNewLooksSettingsListScrollsBehindTheBarInsteadOfEndingAboveIt() {
        val code = SourceContract.code(SourceContract.read(desktopApp)).replace(Regex("\\s+"), " ")
        assertTrue(
            "val settingsBehindBar = com.cosmotune.ui.theme.LocalCosmoShell.current != null && overlays.settingsPage == DesktopSettingsPage.MAIN" in code,
            "only the new look's main Settings list, so the old look and the sub-screens are as they were",
        )
        assertTrue("padding(bottom = if (settingsBehindBar) 0.dp else contentPadding.calculateBottomPadding())" in code, "the box is no shorter than the window then")
        assertTrue("LocalDesktopBottomInset provides if (settingsBehindBar) contentPadding.calculateBottomPadding() else 0.dp" in code, "and its list gets the room as padding")
        assertTrue("contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp + LocalDesktopBottomInset.current)" in code, "inside the scroll")
    }

    // ---- one content margin for every desktop page ---------------------------------------------------------------------------------

    @Test fun everyFamilyOfDesktopPagesStandsAtTheSameMargin() {
        assertEquals(com.cosmotune.design.ShellMetrics.PHONE_PAGE_MARGIN_DP, com.cosmotune.ui.theme.CosmoSpacing.margin.value, "the phone's blocks keep 16, which the inset adds to")
        val collection = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt"))
        assertTrue(Regex("""PAGE_PADDING_X = ${com.cosmotune.design.ShellMetrics.DESKTOP_PAGE_MARGIN_DP.toInt()}\.dp""").containsMatchIn(collection), "the album and playlist page keeps the desktop's margin itself")
        val artist = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopArtistPage.kt"))
        assertTrue("COLUMNS_PADDING = ShellMetrics.DESKTOP_PAGE_MARGIN_DP.dp" in artist, "and so does the artist page")
    }

    @Test fun theBoxThatHoldsADesktopPageMovesThePhoneBlockPagesInByTheMissingTwelve() {
        val code = SourceContract.code(SourceContract.read(desktopApp)).replace(Regex("\\s+"), " ")
        assertTrue(
            "val pageInset = if (com.cosmotune.ui.theme.LocalCosmoShell.current == null) 0f else com.cosmotune.design.ShellMetrics.desktopPageInsetDp( " +
                "pageKeepsPhoneMargin = overlays.settingsPage == null && !overlays.replay && openedArtist == null && openedCollection == null && " +
                "destination != DesktopDestination.LISTEN_NOW, )" in code,
            "every page but Settings, Replay, the artist, the collection and Home is made of the phone's blocks; the look off has no inset",
        )
        assertTrue(".padding(horizontal = pageInset.dp), ) { when {" in code, "in the one box the pages stand in, after its pointer handling")
    }

    @Test fun thePhoneAndTheDesktopSettingsRowsReadTheSameTwoKeys() {
        val phone = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt"))
        val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt"))
        for ((name, code) in listOf("phone" to phone, "desktop" to desktop)) {
            assertTrue("Res.string.cosmo_new_look)" in code && "Res.string.cosmo_new_look_summary)" in code, "the $name row takes its two texts from the shared strings")
        }
    }
}
