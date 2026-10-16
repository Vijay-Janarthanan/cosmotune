package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room of a mix tile (`.mix2`, `.fan`, `.mix-txt`): the board's tile is 172 dp tall with a 96 dp fan 14 dp under its top and a
 * text block standing 12 dp over its bottom, which is only right for a title of one line. A title that takes two lines pushes the text
 * up into the fan, so the fan gives way: first it moves up, then it shrinks, and only past a point it is left out.
 */
class MixFanFitTest {
    /** The height of the text block: [lines] title lines at 22 sp x 1.05 and one subtitle line at 12.5 sp x 1.35, at font scale 1. */
    private fun text(lines: Int, fontScale: Float = 1f) = fontScale * (lines * 22f * 1.05f + 12.5f * 1.35f)

    private fun bottomOf(fit: BlockMetrics.MixFanFit) = fit.topDp + BlockMetrics.MIX_FAN_HEIGHT_DP * fit.scale
    private fun textTop(tile: Float, text: Float) = tile - BlockMetrics.MIX_TEXT_BOTTOM_DP - text

    @Test
    fun aTitleOfOneLineLeavesTheBoardsFanWhereItIs() {
        val fit = BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, text(lines = 1))
        assertTrue(fit.visible)
        assertEquals(1f, fit.scale)
        assertEquals(14f, fit.topDp, "`.fan` stands 14 dp under the top")
    }

    @Test
    fun aTitleOfTwoLinesMakesTheFanGiveWay() {
        val fit = BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, text(lines = 2))
        assertTrue(fit.visible)
        assertTrue(fit.scale < 1f && fit.scale > 0.8f, "a little smaller, not a different picture: ${fit.scale}")
        assertEquals(BlockMetrics.MIX_FAN_TOP_MIN_DP, fit.topDp, "and as high as it may stand")
    }

    @Test
    fun theFanNeverReachesTheTextWhateverTheTextHeightAndTheTile() {
        for (tile in listOf(BlockMetrics.TILE_HEIGHT_DP, 140f, 200f, 224f)) {
            var height = 0f
            while (height <= 140f) {
                val fit = BlockMetrics.mixFanFit(tile, height)
                if (fit.visible) {
                    assertTrue(
                        bottomOf(fit) + BlockMetrics.MIX_FAN_GAP_DP <= textTop(tile, height) + 0.001f,
                        "tile $tile, text $height: the fan ends at ${bottomOf(fit)}, the text starts at ${textTop(tile, height)}",
                    )
                    assertTrue(fit.scale in BlockMetrics.MIX_FAN_MIN_SCALE..1f, "tile $tile, text $height: scale ${fit.scale}")
                    assertTrue(fit.topDp in BlockMetrics.MIX_FAN_TOP_MIN_DP..BlockMetrics.MIX_FAN_TOP_DP, "tile $tile, text $height: top ${fit.topDp}")
                }
                height += 0.5f
            }
        }
    }

    @Test
    fun aTallerTextNeverGivesTheFanMoreRoom() {
        var previous = BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, 0f)
        var height = 0.5f
        while (height <= 140f) {
            val fit = BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, height)
            assertTrue(fit.scale <= previous.scale + 0.0001f || !fit.visible, "scale grew at text $height")
            assertTrue(fit.topDp <= previous.topDp + 0.0001f || !fit.visible, "top grew at text $height")
            if (!previous.visible) assertFalse(fit.visible, "the fan came back at text $height")
            previous = fit
            height += 0.5f
        }
    }

    @Test
    fun theFanIsLeftOutWhenAnythingThatSmallWouldBeLost() {
        // three lines of title is the most the tile draws at font scale 1: still a fan, small
        val three = BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, text(lines = 3))
        assertTrue(three.visible && three.scale < 0.7f, "${three.scale}")
        // two lines at the largest font scales the settings offer
        assertFalse(BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, text(lines = 2, fontScale = 2f)).visible)
        assertFalse(BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, 400f).visible, "a text taller than the tile")
        assertEquals(0f, BlockMetrics.mixFanFit(BlockMetrics.TILE_HEIGHT_DP, 400f).scale)
    }

    @Test
    fun theWideTileIsTallEnoughForTwoLinesAtFullSize() {
        val tile = Bento.WIDE.mixHeight()
        assertEquals(224f, tile)
        val fit = BlockMetrics.mixFanFit(tile, text(lines = 2))
        assertTrue(fit.visible && fit.scale == 1f, "the desktop's 5 x 2 mix: ${fit.scale}")
        assertEquals(BlockMetrics.MIX_FAN_TOP_DP, fit.topDp)
    }

    @Test
    fun theTextBlockOfThePhonesTileIsAsWideAsTheTileLessItsSidesAtEveryWidth() {
        for (window in listOf(360f, 411f, 427f)) {
            val tile = Bento.PHONE.mixWidth(window)
            assertEquals(tile - 2 * BlockMetrics.MIX_TEXT_SIDE_DP, BlockMetrics.mixTextWidthDp(tile))
            assertTrue(BlockMetrics.mixTextWidthDp(tile) > 0f)
        }
        assertEquals(0f, BlockMetrics.mixTextWidthDp(20f), "a tile narrower than its sides has no text width, never a negative one")
    }

    @Test
    fun theFansOwnNumbersAreTheBoards() {
        assertEquals(132f, BlockMetrics.MIX_FAN_WIDTH_DP)  // .fan width
        assertEquals(96f, BlockMetrics.MIX_FAN_HEIGHT_DP)  // .fan height
        assertEquals(14f, BlockMetrics.MIX_FAN_TOP_DP)     // .fan top
        assertEquals(12f, BlockMetrics.MIX_FAN_END_DP)     // .fan right
        assertEquals(14f, BlockMetrics.MIX_TEXT_SIDE_DP)   // .mix-txt left, right
        assertEquals(12f, BlockMetrics.MIX_TEXT_BOTTOM_DP) // .mix-txt bottom
    }
}
