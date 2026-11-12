package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BC-30: in every list that shows songs or song cards, on both hosts, only the row that was played lights (the
 * list asks `playingRowMatcher` / `playingShelfItemMatcher` once), not every namesake (`isSameTrackAs` per row); and the song rows
 * show the source's offered quality with the player badge's glyph. The matchers themselves are tested in `PlayingRowMatcherTest`
 * and `PlayingShelfItemMatcherTest`; the rows are rendered in `PlayingRowInteractionTest`.
 */
class PlayingRowContractTest {

    private val songLists = listOf(
        "app/src/main/java/com/cosmotune/music/ui/screens/DetailScreen.kt",
        "app/src/main/java/com/cosmotune/music/ui/screens/LocalMusicScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearchResults.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearch.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoReleasePage.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitArtist.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/library/CosmoHistory.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/library/CosmoDeviceMusic.kt",
        "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt",
    )

    private val shelves = listOf(
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/screens/HomeScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks/CosmoCards.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/home/OrbitHome.kt",
    )

    @Test fun everySongListAsksTheMatcherAndNoRowLightsByNameAlone() {
        for (path in songLists) {
            val code = SourceContract.code(SourceContract.read(path))
            assertFalse("isSameTrackAs(" in code, "$path lights rows by name alone")
            assertTrue("playingRowMatcher(" in code, "$path asks playingRowMatcher")
        }
    }

    @Test fun everyShelfAsksTheShelfMatcher() {
        for (path in shelves) {
            val code = SourceContract.code(SourceContract.read(path))
            assertTrue("playingShelfItemMatcher(" in code, "$path asks playingShelfItemMatcher")
            assertFalse("matchesCurrentlyPlaying(" in code, "$path keeps the old per-card rule")
            assertFalse(Regex("""\bitem\.isSong\(""").containsMatchIn(code), "$path lights cards one by one")
        }
    }

    @Test fun theDesktopCollectionPageIsTheMatchingOne() {
        val code = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))
        // The collection page is the Orbit one, told the playing song; it asks playingRowMatcher itself (songLists above).
        val page = SourceContract.firstStatement(SourceContract.functionBody(code, "DesktopCollectionPage"))
        assertTrue(page.startsWith("com.cosmotune.desktop.CosmoDesktopCollectionPage(") && "nowPlaying = LocalNowPlaying.current" in page, page)
    }

    @Test fun desktopRowsDrawTheSourceQualityWithThePlayerBadge() {
        val app = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))
        // The collection page's rows are the shared Orbit row, which draws the player badge's tier (Common.kt's SongRow).
        val row = SourceContract.code(SourceContract.functionBody(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt"), "CollectionTrackRow"))
        assertTrue("OrbitTrackRow(" in row && "showQuality = true" in row, "the desktop rows ask the shared row for the quality")
        val common = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/Common.kt"))
        assertTrue("if (showSourceQuality) song.sourceQualityBadge() else null" in common)
        val parts = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopSongRow.kt"))
        assertFalse("SHOWN_QUALITIES" in parts || "setOf(\"LOSSLESS\"" in parts, "no second, text-only list of tiers")
    }

    @Test fun thePhoneRowsCarryTheQualityFlagToTheLooksRow() {
        val common = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/Common.kt")
        assertTrue("showSourceQuality: Boolean = false" in SourceContract.parameters(common, "SongRow").joinToString())
        val delegate = SourceContract.firstStatement(SourceContract.functionBody(common, "SongRow"))
        assertTrue("showSourceQuality = showSourceQuality" in delegate, "the delegate gets the flag")
        val cosmo = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks/CosmoSongRow.kt")
        assertTrue("showSourceQuality: Boolean = false" in SourceContract.parameters(cosmo, "CosmoSongRow").joinToString())
        // The new look draws the offer as a compact catalogue pill (QualityBadge), still only when the flag asks for it.
        assertTrue("if (showSourceQuality) song.catalogueQualityBadge() else null" in SourceContract.code(cosmo))
    }
}
