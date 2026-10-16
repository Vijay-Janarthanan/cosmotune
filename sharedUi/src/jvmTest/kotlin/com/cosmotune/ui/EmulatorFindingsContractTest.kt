package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
