package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The numbers of the content blocks (rows, cards, mood tiles), each with the style rule it comes from. */
class BlockMetricsTest {

    @Test
    fun theRowHeightsAreThoseOfTheBoards() {
        assertEquals(64f, BlockMetrics.ROW_DP)           // .mrow
        assertEquals(60f, BlockMetrics.TRACK_ROW_DP)     // .tracks2 .mrow
        assertEquals(54f, BlockMetrics.DESKTOP_ROW_DP)   // .d2-tracks li
        assertEquals(52f, BlockMetrics.ROW_COVER_DP)     // .mrow .cv
    }

    @Test
    fun theCardWidthsAreThoseOfTheBoards() {
        assertEquals(168f, BlockMetrics.LEAD_CARD_DP)
        assertEquals(112f, BlockMetrics.SMALL_CARD_DP)
        assertEquals(150f, BlockMetrics.CHART_CARD_DP)
    }

    @Test
    fun theMoodTilesAre92AndWhenBig116() {
        assertEquals(92f, BlockMetrics.MOOD_TILE_DP)
        assertEquals(116f, BlockMetrics.MOOD_TILE_BIG_DP)
        assertEquals(92f, BlockMetrics.moodTileHeightDp(big = false))
        assertEquals(116f, BlockMetrics.moodTileHeightDp(big = true))
    }

    @Test
    fun theThreeCardsOfTheBoardsKeepTheirInlineRadii() {
        assertEquals(22f, BlockMetrics.cardRadiusDp(BlockMetrics.LEAD_CARD_DP))    // lead 168: --r:22px
        assertEquals(20f, BlockMetrics.cardRadiusDp(BlockMetrics.CHART_CARD_DP))   // chart 150: --r:20px
        assertEquals(16f, BlockMetrics.cardRadiusDp(BlockMetrics.SMALL_CARD_DP))   // small 112: --r:16px
    }

    @Test
    fun theCardRadiusStepsAtOneSixtyOneFortyAndOneTwenty() {
        assertEquals(22f, BlockMetrics.cardRadiusDp(160f))
        assertEquals(22f, BlockMetrics.cardRadiusDp(240f))
        assertEquals(20f, BlockMetrics.cardRadiusDp(159.9f))
        assertEquals(20f, BlockMetrics.cardRadiusDp(140f))
        assertEquals(18f, BlockMetrics.cardRadiusDp(139.9f))
        assertEquals(18f, BlockMetrics.cardRadiusDp(120f))
        assertEquals(16f, BlockMetrics.cardRadiusDp(119.9f))
        assertEquals(16f, BlockMetrics.cardRadiusDp(40f))
        assertEquals(16f, BlockMetrics.cardRadiusDp(0f))
    }

    @Test
    fun noMoodsMakeNoRows() {
        assertEquals(emptyList(), BlockMetrics.moodRows(0))
        assertEquals(emptyList(), BlockMetrics.moodRows(-3))
    }

    @Test
    fun theFirstTwoMoodsAreBigTwoToARow() {
        val rows = BlockMetrics.moodRows(2)
        assertEquals(listOf(BlockMetrics.MoodRow(first = 0, size = 2, big = true)), rows)
        assertEquals(2, rows.single().slots)
    }

    @Test
    fun aSingleMoodIsAHalfWidthBigTile() {
        val row = BlockMetrics.moodRows(1).single()
        assertEquals(BlockMetrics.MoodRow(first = 0, size = 1, big = true), row)
        assertEquals(2, row.slots)   // the other half stays empty, as a grid column would
    }

    @Test
    fun theRestFollowThreeToARow() {
        // the board: Unwind, Focus (big), then Lift, Drive, Rain
        assertEquals(
            listOf(BlockMetrics.MoodRow(0, 2, true), BlockMetrics.MoodRow(2, 3, false)),
            BlockMetrics.moodRows(5),
        )
        assertEquals(3, BlockMetrics.moodRows(5)[1].slots)
    }

    @Test
    fun aShortLastRowKeepsTheWidthOfAFullOne() {
        val rows = BlockMetrics.moodRows(9)
        assertEquals(listOf(2, 3, 3, 1), rows.map { it.size })
        assertEquals(listOf(true, false, false, false), rows.map { it.big })
        assertEquals(listOf(0, 2, 5, 8), rows.map { it.first })
        assertEquals(3, rows.last().slots)   // one tile, a third of the width: two slots stay empty
    }

    @Test
    fun everyMoodIsInExactlyOneRowInOrder() {
        for (count in 0..40) {
            val rows = BlockMetrics.moodRows(count)
            assertEquals(count, rows.sumOf { it.size })
            var next = 0
            for (row in rows) {
                assertEquals(next, row.first)
                next += row.size
            }
        }
    }

    @Test
    fun aTileSpansTwoColumnsOfSixAndABigOneThree() {
        // a 360 dp window: margin 16, 5 gaps of 10, six columns
        val column = (360f - 2 * 16f - 5 * 10f) / 6f
        assertEquals(2 * column + 10f, BlockMetrics.moodTileWidthDp(360f, big = false), 0.001f)
        assertEquals(3 * column + 2 * 10f, BlockMetrics.moodTileWidthDp(360f, big = true), 0.001f)
        // two big tiles and the gap between them fill the content width
        assertEquals(360f - 32f, 2 * BlockMetrics.moodTileWidthDp(360f, big = true) + 10f, 0.001f)
        assertEquals(360f - 32f, 3 * BlockMetrics.moodTileWidthDp(360f, big = false) + 2 * 10f, 0.001f)
    }

    @Test
    fun theCurrentRowWashIsDrawnEightDpInWithRadiusEighteen() {
        assertEquals(8f, BlockMetrics.ROW_WASH_INSET_DP)
        assertEquals(18f, BlockMetrics.ROW_WASH_RADIUS_DP)
        // --row-on: accentContainer at 70 %, then at 20 % by 75 %, then transparent
        assertEquals(0.7f, BlockMetrics.ROW_WASH_START_ALPHA)
        assertEquals(0.2f, BlockMetrics.ROW_WASH_MID_ALPHA)
        assertEquals(0.75f, BlockMetrics.ROW_WASH_MID_STOP)
    }

    @Test
    fun theEqualiserHasThreeBarsOfThreeDpTwoApartAndRestsAtSixtyHundredForty() {
        assertEquals(14f, BlockMetrics.EQ_HEIGHT_DP)
        assertEquals(3f, BlockMetrics.EQ_BAR_DP)
        assertEquals(2f, BlockMetrics.EQ_GAP_DP)
        assertEquals(2f, BlockMetrics.EQ_RADIUS_DP)
        assertEquals(listOf(0.6f, 1f, 0.4f), (0..2).map { BlockMetrics.equaliserRest(it) })
    }

    @Test
    fun everyEqualiserBarHasRoomToMoveAndStaysInsideItsBox() {
        for (i in 0..2) {
            val rest = BlockMetrics.equaliserRest(i)
            val peak = BlockMetrics.equaliserPeak(i)
            assertTrue(peak in 0.3f..1f, "bar $i peak $peak")
            assertTrue(kotlin.math.abs(peak - rest) >= 0.3f, "bar $i moves by at least a third of its box")
        }
    }

    @Test
    fun theSwipeNumbersAreThoseOfTheOldRow() {
        // Common.kt SongRow: commit at 45 % of the width, positionalThreshold = distance * 0.5f, the tick re-arms under 0.8 of its line
        assertEquals(0.45f, BlockMetrics.SWIPE_COMMIT)
        assertEquals(0.5f, BlockMetrics.SWIPE_POSITIONAL_THRESHOLD)
        assertEquals(0.8f, BlockMetrics.SWIPE_REARM)
    }

    @Test
    fun theSwipeRevealIsAContainerOfTwentyWithSeventyTwoDpBlocksOfSixteen() {
        assertEquals(20f, BlockMetrics.SWIPE_CONTAINER_RADIUS_DP)
        assertEquals(0.05f, BlockMetrics.SWIPE_CONTAINER_INK)
        assertEquals(72f, BlockMetrics.SWIPE_ACTION_DP)
        assertEquals(16f, BlockMetrics.SWIPE_ACTION_RADIUS_DP)
        assertEquals(6f, BlockMetrics.SWIPE_ACTION_INSET_DP)
    }

    @Test
    fun aMoodChipIsASquircleAtRestAPillWhenSelectedAndSquaresUpPressed() {
        val rest = BlockMetrics.moodChipTarget(selected = false, pressed = false)
        assertEquals(13.2f, rest.radiusDp, 0.0001f)                         // 0.30 x 44
        assertEquals(4f, rest.exponent)
        assertEquals(1f, rest.scale)
        val on = BlockMetrics.moodChipTarget(selected = true, pressed = false)
        assertEquals(MorphTarget(22f, 2f, 1f), on)                          // 0.5 x 44: a pill
        val pressed = BlockMetrics.moodChipTarget(selected = false, pressed = true)
        assertEquals(8.8f, pressed.radiusDp, 0.0001f)                       // 0.20 x 44
        assertEquals(4f, pressed.exponent)
        assertEquals(0.965f, pressed.scale)                                 // the B width squish
        assertEquals(1f, pressed.scaleY)                                    // the height holds
    }

    @Test
    fun aSelectedMoodChipPressedLooksLikeAnyOtherPressedOne() {
        assertEquals(BlockMetrics.moodChipTarget(false, true), BlockMetrics.moodChipTarget(true, true))
    }

    @Test
    fun aMoodTileIsRadiusTwentyAndPressesLikeTheChip() {
        assertEquals(MorphTarget(20f, 4f, 1f), BlockMetrics.moodTileTarget(pressed = false))
        val pressed = BlockMetrics.moodTileTarget(pressed = true)
        assertEquals(20f * 0.20f / 0.30f, pressed.radiusDp, 0.0001f)
        assertEquals(0.965f, pressed.scale)
        assertEquals(1f, pressed.scaleY)
    }

    @Test
    fun theLikedAndMixTilesAre172TallWithRadius26AndShrinkTwoPerCentWhenPressed() {
        assertEquals(172f, BlockMetrics.TILE_HEIGHT_DP)
        assertEquals(MorphTarget(26f, 4f, 1f), BlockMetrics.tileTarget(pressed = false))
        assertEquals(MorphTarget(26f, 4f, 0.98f), BlockMetrics.tileTarget(pressed = true))
    }
}
