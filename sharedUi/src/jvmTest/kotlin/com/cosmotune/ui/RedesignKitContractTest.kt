package com.cosmotune.ui

import com.cosmotune.ui.orbit.ORBIT_UNDO_MS
import com.cosmotune.ui.orbit.OrbitHeaderSize
import com.cosmotune.ui.orbit.OrbitRowMetrics
import com.cosmotune.ui.orbit.OrbitSpace
import com.cosmotune.ui.orbit.OrbitUndoState
import com.cosmotune.ui.orbit.orbitCountText
import com.cosmotune.ui.orbit.orbitRecordGrid
import com.cosmotune.ui.shell.CosmoNav
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The redesign's shared pieces (the shared board's A1 to A5 and the Music board's spec cards): the compact page header, the flat row
 * with its Comfortable and Compact sizes, the undo snackbar, the shaped placeholders, the counts, the one section rhythm and the
 * navigation rule. Read off the numbers and the source, as the other contract tests do.
 */
class RedesignKitContractTest {
    private val orbit = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit"
    private fun code(path: String) = SourceContract.code(SourceContract.read(path))

    @Test fun theCompactHeaderIsTheBoardsCard() {
        assertEquals(220.dp, OrbitHeaderSize.PAGE.height, "the colour card is used once, 220 dp tall")
        assertEquals(120.dp, OrbitHeaderSize.PAGE.disc, "a 120 dp cover disc beside the words")
        assertEquals(30f, OrbitHeaderSize.PAGE.title.value, "a 30 sp title")
        assertEquals(160.dp, OrbitHeaderSize.PICK_UP.height, "Home's pick-up card is the header without its actions")
        assertEquals(120.dp, OrbitHeaderSize.RESULT.height, "Search's top result is 120 dp")
        assertEquals(80.dp, OrbitHeaderSize.RESULT.disc)
        val header = code("$orbit/OrbitHeader.kt")
        val body = SourceContract.functionBody(header, "OrbitPageHeader")
        assertTrue("maxLines = 2" in body, "the title takes two lines at most")
        assertTrue("OrbitPlayButton(" in body && "size.playInset" in body, "Play stands in the bite")
        assertTrue("buttons(colors)" in body, "one row of round actions")
        assertTrue("fun PublishCollapsedHeader(" in header && "fun OrbitCollapsedHeaderRow(" in header, "the heading collapses into the top bar with Play")
        val bar = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome/CosmoTopBar.kt")
        assertTrue("collapsedHeader != null && scrolled" in bar && "OrbitCollapsedHeaderRow(collapsedHeader" in bar, "the phone's top bar draws it once scrolled")
    }

    @Test fun theRowIsFlatSixtyOrFiftyTwo() {
        assertEquals(60f, OrbitRowMetrics.COMFORTABLE_DP)
        assertEquals(52f, OrbitRowMetrics.COMPACT_DP)
        assertEquals(48f, OrbitRowMetrics.DISC_DP)
        assertEquals(40f, OrbitRowMetrics.COMPACT_DISC_DP)
        val rows = code("$orbit/OrbitRows.kt")
        val row = SourceContract.functionBody(rows, "OrbitSongRow")
        assertFalse(".background(" in row.substringBefore("leading?.invoke(this)"), "no card behind the row")
        assertTrue("number != null -> if (now) OrbitPlayingBars(look.accent, playing) else OrbitTrackNumber(number, look)" in row, "a track number turns into peach bars while it plays")
        assertTrue("OrbitRing(box, look.accent" in row, "the playing disc wears its orbit")
        assertTrue("height: Dp = orbitRowHeight()" in SourceContract.read("$orbit/OrbitRows.kt"), "the row's height follows Look and colours, Row size")
        val setting = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/settings/RowSizeSetting.kt")
        assertTrue("\"Comfortable\", \"Compact\"" in setting && "setCompactRows" in setting)
        assertTrue("CosmoRowSizeSetting()" in SourceContract.read("app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt"), "the phone's Look and colours offers it")
        assertTrue("CosmoRowSizeSetting()" in SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt"), "and the desktop's")
    }

    @Test fun removedOffersUndoForTenSeconds() {
        assertEquals(10_000L, ORBIT_UNDO_MS)
        var restored = 0
        val undo = OrbitUndoState()
        undo.removed("Song title") { restored++ }
        assertEquals("Removed “Song title”", undo.offer?.message)
        undo.undo()
        assertEquals(1, restored)
        assertNull(undo.offer, "Undo takes the snackbar away")
        val first = OrbitUndoState().apply { removed("One") {} }
        val shown = first.offer!!
        first.removed("Two") {}
        first.dismiss(shown)
        assertEquals("Removed “Two”", first.offer?.message, "an older offer's time running out leaves the newer one")
        val chrome = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoBottomChrome.kt")
        assertTrue("OrbitUndoHost(com.cosmotune.ui.orbit.LocalOrbitUndo.current)" in chrome, "it stands over the mini player in every mode")
    }

    @Test fun countsAndRhythm() {
        assertEquals("20", orbitCountText(20))
        assertEquals("12K", orbitCountText(12_400))
        assertEquals(24.dp, OrbitSpace.section)
        assertEquals(10.dp, OrbitSpace.sectionToContent)
        val kit = SourceContract.read("$orbit/OrbitKit.kt")
        assertTrue("fontSize = 30.sp" in kit.substringAfter("val greeting"), "page headings are 30 sp")
        val (columns, cell) = orbitRecordGrid(412.dp, 16.dp)
        assertEquals(3, columns, "three columns of records on a phone")
        assertTrue(cell in 110.dp..124.dp, "about 118 dp each")
    }

    @Test fun theNavigationRuleIsBarUnder600AndRailFrom600() {
        assertFalse(CosmoNav.usesRail(599.dp))
        assertTrue(CosmoNav.usesRail(600.dp))
        assertEquals(88f, CosmoNav.RAIL_WIDTH_DP)
        val shell = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoNavShell.kt")
        assertTrue("if (rail) CosmoNavRail(" in shell && "bottomChrome(!rail," in shell, "the shell decides: the rail and the bare mini player, or the bar")
        val chrome = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/CosmoBottomChrome.kt")
        assertTrue("DockSearchButton(search)" in chrome, "Search is a round button beside the tabs")
        val phone = SourceContract.read("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        assertTrue("if (musicRail) {" in phone && "com.cosmotune.ui.shell.CosmoNavRail(" in phone, "Music on the phone has the rail from 600 dp")
        assertTrue("showTabs = !musicRail," in phone && "search = musicSearch," in phone)
        assertTrue("count = unreadReleases" in phone, "the Releases tab carries its count")
        val desktop = SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue("val railed = compact && com.cosmotune.ui.shell.CosmoNav.usesRail(maxWidth)" in desktop, "a desktop window between 600 dp and the sidebar has the rail")
        val kitRail = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/kit/KitStates.kt")
        assertTrue("com.cosmotune.ui.shell.CosmoNavRail(" in kitRail, "one rail: the kit's is the shell's")
    }

    @Test fun placeholdersTakeThePagesShape() {
        val header = code("$orbit/OrbitHeader.kt")
        assertTrue("fun OrbitHeaderSkeleton(" in header && "fun OrbitRowSkeleton(" in header && "fun OrbitRecordGridSkeleton(" in header)
        val release = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pages/CosmoReleasePage.kt")
        assertTrue("orbitRowSkeletons(\"skeleton:release\", side, rows = 8, numbered = album)" in release, "an album loads as numbered rows")
    }
}
