package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BC-30: in every list that shows songs or song cards, on both looks and both hosts, only the row that was played lights (the
 * list asks `playingRowMatcher` / `playingShelfItemMatcher` once), not every namesake (`isSameTrackAs` per row); and the song rows
 * show the source's offered quality with the player badge's glyph. The matchers themselves are tested in `PlayingRowMatcherTest`
 * and `PlayingShelfItemMatcherTest`; the rows are rendered in `PlayingRowInteractionTest`.
 */
class PlayingRowContractTest {

    private val songLists = listOf(
        "app/src/main/java/com/cosmotune/music/ui/screens/DetailScreen.kt",
        "app/src/main/java/com/cosmotune/music/ui/screens/HistoryScreen.kt",
        "app/src/main/java/com/cosmotune/music/ui/screens/LocalMusicScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/screens/SearchScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearchResults.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearch.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoReleasePage.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoArtistPage.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/library/CosmoHistory.kt",
        "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopCollectionPage.kt",
        "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopArtistPage.kt",
    )

    private val shelves = listOf(
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/screens/HomeScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks/CosmoCards.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/home/CosmoHome.kt",
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

    @Test fun theClassicDesktopListsGiveTheirRowsTheMatcher() {
        val code = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))
        assertTrue("private fun rememberPlayingRowMatcher(rows: List<Song>)" in code)
        // The row asks the list being drawn first and falls back to the name rule only outside one.
        assertTrue("LocalPlayingRow.current?.invoke(this) ?: isSameTrackAs(LocalNowPlaying.current)" in code)
        // History and the library's songs hand the matcher down; the collection page passes each row its answer.
        assertEquals(2, Regex("""PlayingRowScope\((history|songs)\) \{""").findAll(code).count())
        assertTrue("isCurrent = isCurrentRow(song)" in SourceContract.functionBody(code, "DesktopCollectionPage"))
        assertTrue("isCurrent: Boolean? = null" in SourceContract.parameters(code, "DesktopCollectionSongRow").joinToString())
        assertTrue("val nowPlaying = isCurrent ?: song.isNowPlaying()" in SourceContract.functionBody(code, "DesktopCollectionSongRow"))
    }

    @Test fun desktopRowsDrawTheSourceQualityWithThePlayerBadge() {
        val classic = SourceContract.code(SourceContract.functionBody(
            SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"), "DesktopSongRow"))
        assertTrue("song.sourceQualityBadge()?.let" in classic && "SourceQualityBadge(" in classic)
        assertFalse("setOf(\"LOSSLESS\"" in classic, "no second, text-only list of tiers")
        val cosmo = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopSongRow.kt"))
        assertTrue("song.sourceQualityBadge() ?: return" in cosmo && "SourceQualityBadge(" in cosmo)
        assertFalse("SHOWN_QUALITIES" in cosmo)
    }

    @Test fun thePhoneRowsCarryTheQualityFlagThroughBothLooks() {
        val common = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/Common.kt")
        assertTrue("showSourceQuality: Boolean = false" in SourceContract.parameters(common, "SongRow").joinToString())
        val delegate = SourceContract.firstStatement(SourceContract.functionBody(common, "SongRow"))
        assertTrue("showSourceQuality = showSourceQuality" in delegate, "the new-look delegate gets the flag")
        val cosmo = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks/CosmoSongRow.kt")
        assertTrue("showSourceQuality: Boolean = false" in SourceContract.parameters(cosmo, "CosmoSongRow").joinToString())
        assertTrue("if (showSourceQuality) song.sourceQualityBadge() else null" in SourceContract.code(cosmo))
    }
}
