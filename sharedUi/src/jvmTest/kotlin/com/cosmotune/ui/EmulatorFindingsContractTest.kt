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

    @Test fun theMixTileKeepsItsArtAndItsTextApart() {
        // DESIGN_SYSTEM.md R5 removed the fan the finding was about. What it asked for stays: the art and the text never overlap, because they
        // stand side by side in a row, and the title takes up to two lines.
        val kit = SourceContract.read("$ui/kit/KitCards.kt")
        val tile = SourceContract.code(SourceContract.functionBody(kit, "KitQuickTile")).replace(Regex("\\s+"), " ")
        assertTrue("Row(" in tile && "Modifier.weight(1f)" in tile, "the title takes what the art leaves")
        assertTrue("maxLines = 2" in tile, "a title takes up to two lines")
        assertFalse("rotationZ" in tile, "nothing is tilted")
        assertFalse("CosmoMixTile" in SourceContract.read("$ui/library/CosmoLibrary.kt"), "the library draws no fan")
    }

    // ---- the Settings row of the new look: once no longer a preview, now gone with the switch, in every language ---------------------

    private val resources = java.io.File(SourceContract.root, "sharedUi/src/commonMain/composeResources")

    private fun valueOf(folder: String, key: String): String {
        val text = java.io.File(resources, "$folder/strings.xml").readText()
        val match = Regex("""<string name="$key">([^<]*)</string>""").find(text)
        return (match ?: fail("$folder has no $key")).groupValues[1]
    }

    private val folders get() = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().map { it.name }.sorted()

    // ---- the desktop playlist page has no album column when no row has an album --------------------------------------------------

    @Test fun theDesktopCollectionPageDecidesTheAlbumColumnOnEveryRowItHasLoadedWithTheSharedRule() {
        val page = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt")).replace(Regex("\\s+"), " ")
        assertTrue("val showAlbum = remember(songs, isAlbum) { CollectionColumns.showsAlbum(isAlbum, songs.map { it.albumName }) }" in page, "the shared rule, over all the rows, not the filtered ones")
        assertTrue("showAlbum = showAlbum," in page, "and the rows get its answer")
        assertFalse("showAlbum = !isAlbum" in page, "the old rule, which drew a dash on every row of a playlist without albums, is gone")
        val row = SourceContract.code(SourceContract.functionBody(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt"), "CollectionTrackRow")).replace(Regex("\\s+"), " ")
        assertTrue("song.albumName?.takeIf { showAlbum && it.isNotBlank() }" in row, "a row names its album only where the rule says rows name one")
    }

    // ---- the desktop page scrolls to the window's edge: the bar's room is content padding, and only while the bar is there --------------

    private val desktopApp = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"

    @Test fun theDesktopFramePadsTheContentForTheBarByTheSharedRuleAndOnlyWithASong() {
        val frame = SourceContract.code(SourceContract.functionBody(SourceContract.read(desktopApp), "DesktopFrame")).replace(Regex("\\s+"), " ")
        assertTrue(
            "bottom = com.cosmotune.design.ShellMetrics.desktopPageBottomDp(compact, songShown = LocalNowPlaying.current != null).dp," in frame,
            "the page pads by the pure rule, with whether a song is selected",
        )
    }

    @Test fun theSettingsListScrollsBehindTheBarInsteadOfEndingAboveIt() {
        val code = SourceContract.code(SourceContract.read(desktopApp)).replace(Regex("\\s+"), " ")
        assertTrue(
            "val settingsBehindBar = overlays.settingsPage == DesktopSettingsPage.MAIN" in code,
            "only the main Settings list, so the sub-screens are as they were",
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
            "val pageInset = com.cosmotune.design.ShellMetrics.desktopPageInsetDp( " +
                "pageKeepsPhoneMargin = overlays.settingsPage == null && !overlays.replay && openedArtist == null && openedCollection == null && " +
                "destination != DesktopDestination.LISTEN_NOW, )" in code,
            "every page but Settings, Replay, the artist, the collection and Home is made of the phone's blocks",
        )
        assertTrue(".padding(horizontal = pageInset.dp), ) { when {" in code, "in the one box the pages stand in, after its pointer handling")
    }

    @Test fun thePhoneAndTheDesktopSettingsRowsReadTheSameTwoKeys() {
        val phone = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt"))
        val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt"))
        for ((name, code) in listOf("phone" to phone, "desktop" to desktop)) {
            assertTrue("Res.string.cosmo_cover_colours)" in code && "Res.string.cosmo_cover_colours_summary)" in code, "the $name first row takes its two texts from the shared strings")
        }
    }
}
