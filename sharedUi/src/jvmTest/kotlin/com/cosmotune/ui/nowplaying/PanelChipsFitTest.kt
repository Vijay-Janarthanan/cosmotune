package com.cosmotune.ui.nowplaying

import kotlin.test.Test
import kotlin.test.assertEquals

/** Lyrics, Up next and Related in a narrow side column: they go on to a second line rather than being cut ("⊘R"). */
class PanelChipsFitTest {
    @Test fun chipsThatFitStayOnOneLine() {
        assertEquals(listOf(listOf(0, 1, 2)), panelChipLines(listOf(100, 120, 110), gap = 8, max = 400))
        assertEquals(1, fitPanelChips(listOf(100, 120, 110), gap = 8, max = 346))
    }

    @Test fun theChipThatDoesNotFitStartsTheNextLine() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), panelChipLines(listOf(100, 120, 110), gap = 8, max = 345))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), panelChipLines(listOf(100, 120, 110), gap = 8, max = 150))
    }

    @Test fun aChipWiderThanTheColumnStillHasALineOfItsOwn() {
        assertEquals(listOf(listOf(0), listOf(1)), panelChipLines(listOf(300, 80), gap = 8, max = 200))
    }
}
