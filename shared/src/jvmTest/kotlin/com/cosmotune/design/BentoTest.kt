package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bento grid of Home (`02-design-formulas.md` section 8, `screens-v2.css` `.bento2`, `screens-v3.css` `.dh-bento`). */
class BentoTest {

    // ---- the formulas, on the worked example of the formulas file: a 390 dp phone ------------------------------------------

    @Test
    fun aCellOfA390DpPhoneIs80AndAHalf() {
        assertEquals(80.5f, Bento.cellWidth(windowDp = 390f, columns = 4, marginDp = 16f, gutterDp = 12f))
    }

    @Test
    fun aSpanOfNColumnsIsNCellsAndNMinusOneGutters() {
        val cell = 80.5f
        assertEquals(cell, Bento.spanWidth(1, 390f, 4, 16f, 12f))
        assertEquals(2 * cell + 12f, Bento.spanWidth(2, 390f, 4, 16f, 12f))
        assertEquals(3 * cell + 24f, Bento.spanWidth(3, 390f, 4, 16f, 12f))
        assertEquals(4 * cell + 36f, Bento.spanWidth(4, 390f, 4, 16f, 12f))
    }

    @Test
    fun theHeroIs358By172AndAMixIs173By172() {
        assertEquals(358f, Bento.spanWidth(4, 390f, 4, 16f, 12f))      // all four columns: the page less its margins
        assertEquals(172f, Bento.spanHeight(2, 80f, 12f))              // 2 x 80 and one gutter
        assertEquals(173f, Bento.spanWidth(2, 390f, 4, 16f, 12f))
        assertEquals(172f, Bento.spanHeight(2, 80f, 12f))
    }

    @Test
    fun aSpanOfRowsIsRRowsAndRMinusOneGutters() {
        assertEquals(80f, Bento.spanHeight(1, 80f, 12f))
        assertEquals(172f, Bento.spanHeight(2, 80f, 12f))
        assertEquals(464f, Bento.spanHeight(4, 104f, 16f))             // the desktop hero: 4 x 104 and three gutters
    }

    @Test
    fun anEmptySpanIsNothingAndNotMinusAGutter() {
        assertEquals(0f, Bento.spanWidth(0, 390f, 4, 16f, 12f))
        assertEquals(0f, Bento.spanHeight(0, 80f, 12f))
    }

    @Test
    fun aWindowTooNarrowForItsMarginsGivesNoNegativeCell() {
        assertEquals(0f, Bento.cellWidth(windowDp = 40f, columns = 4, marginDp = 16f, gutterDp = 12f))
    }

    // ---- the phone grid: 4 columns, margin 16, gutter 12, rows of 80; the hero 4 x 2 and a mix 2 x 2 ---------------------------

    @Test
    fun thePhoneGridIsTheBoards() {
        val grid = Bento.PHONE
        assertEquals(4, grid.columns)
        assertEquals(16f, grid.marginDp)
        assertEquals(12f, grid.gutterDp)
        assertEquals(80f, grid.unitDp)
        assertEquals(4 to 2, grid.heroColumns to grid.heroRows)
        assertEquals(2 to 2, grid.mixColumns to grid.mixRows)
    }

    @Test
    fun onThePhoneTwoMixesAndTheirGutterAreAsWideAsTheHero() {
        for (window in listOf(320f, 360f, 390f, 411f, 600f, 839f)) {
            val grid = Bento.PHONE
            assertEquals(grid.heroWidth(window), 2 * grid.mixWidth(window) + grid.gutterDp, 0.001f, "at $window dp")
            assertEquals(window - 2 * grid.marginDp, grid.heroWidth(window), 0.001f, "at $window dp")
        }
    }

    @Test
    fun onThePhoneAMixIsAsTallAsTheBlocksTile() {
        // BlockMetrics.TILE_HEIGHT_DP is the height CosmoMixTile draws at: the grid must agree with it
        assertEquals(BlockMetrics.TILE_HEIGHT_DP, Bento.PHONE.mixHeight())
        assertEquals(Bento.PHONE.heroHeight(), Bento.PHONE.mixHeight())
    }

    // ---- the desktop grid (`.dh-bento`): 12 columns, rows of 104, gap 16; the hero 7 x 4, a mix 5 x 2 -------------------------

    @Test
    fun theWideGridIsTheBoards() {
        val grid = Bento.WIDE
        assertEquals(12, grid.columns)
        assertEquals(28f, grid.marginDp)
        assertEquals(16f, grid.gutterDp)
        assertEquals(104f, grid.unitDp)
        assertEquals(7 to 4, grid.heroColumns to grid.heroRows)
        assertEquals(5 to 2, grid.mixColumns to grid.mixRows)
    }

    @Test
    fun aThousandDpWidePageHasCellsOf64() {
        assertEquals(64f, Bento.cellWidth(1000f, 12, 28f, 16f))
        assertEquals(544f, Bento.WIDE.heroWidth(1000f))                // 7 x 64 and six gutters
        assertEquals(384f, Bento.WIDE.mixWidth(1000f))                 // 5 x 64 and four gutters
        assertEquals(464f, Bento.WIDE.heroHeight())
        assertEquals(224f, Bento.WIDE.mixHeight())
    }

    @Test
    fun onTheDesktopTheHeroAndAMixFillTheRowAndTwoMixesFillTheHeroHeight() {
        for (window in listOf(840f, 960f, 1000f, 1280f, 1920f)) {
            val grid = Bento.WIDE
            assertEquals(window - 2 * grid.marginDp, grid.heroWidth(window) + grid.gutterDp + grid.mixWidth(window), 0.01f, "at $window dp")
        }
        assertEquals(Bento.WIDE.heroHeight(), 2 * Bento.WIDE.mixHeight() + Bento.WIDE.gutterDp, 0.001f)
    }

    // ---- which grid a page gets ---------------------------------------------------------------------------------------------

    @Test
    fun aPageFrom840DpUpGetsTheWideGridAndAnyNarrowerOneThePhoneGrid() {
        assertEquals(840f, Bento.WIDE_MIN_DP)
        assertTrue(Bento.gridFor(839.9f) === Bento.PHONE)
        assertTrue(Bento.gridFor(840f) === Bento.WIDE)
        assertTrue(Bento.gridFor(1280f) === Bento.WIDE)
        assertTrue(Bento.gridFor(360f) === Bento.PHONE)
    }
}
