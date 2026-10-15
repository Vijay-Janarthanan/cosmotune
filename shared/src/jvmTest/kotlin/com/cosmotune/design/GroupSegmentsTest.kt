package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tiles of a settings group (`components-v3bc.css:135-137`): 3 dp apart, outer corners 24, inner corners 6. */
class GroupSegmentsTest {
    @Test fun aGroupWithoutGapsIsOneTileRoundOnAllFourCorners() {
        val s = GroupSegments.of(200f, emptyList())
        assertEquals(1, s.size)
        assertEquals(GroupSegments.Segment(0f, 200f, 24f, 24f), s[0])
    }

    @Test fun oneGapMakesAFirstTileRoundAtTheTopAndALastTileRoundAtTheBottom() {
        val s = GroupSegments.of(200f, listOf(98f..101f))
        assertEquals(listOf(GroupSegments.Segment(0f, 98f, 24f, 6f), GroupSegments.Segment(101f, 200f, 6f, 24f)), s)
    }

    @Test fun theTilesBetweenTwoGapsAreSixAllRound() {
        val s = GroupSegments.of(300f, listOf(100f..103f, 200f..203f))
        assertEquals(3, s.size)
        assertEquals(GroupSegments.Segment(0f, 100f, 24f, 6f), s[0])
        assertEquals(GroupSegments.Segment(103f, 200f, 6f, 6f), s[1])
        assertEquals(GroupSegments.Segment(203f, 300f, 6f, 24f), s[2])
    }

    @Test fun gapsInAnyOrderGiveTheSameTiles() {
        val inOrder = GroupSegments.of(300f, listOf(100f..103f, 200f..203f))
        val shuffled = GroupSegments.of(300f, listOf(200f..203f, 100f..103f))
        assertEquals(inOrder, shuffled)
    }

    @Test fun overlappingOrTouchingGapsAreOneGapSoNoEmptyTileAppears() {
        val s = GroupSegments.of(300f, listOf(100f..103f, 103f..106f, 105f..110f))
        assertEquals(2, s.size)
        assertEquals(GroupSegments.Segment(0f, 100f, 24f, 6f), s[0])
        assertEquals(GroupSegments.Segment(110f, 300f, 6f, 24f), s[1])
    }

    @Test fun aGapAtTheTopOrTheBottomLeavesNoEmptyTile() {
        val atTop = GroupSegments.of(100f, listOf(0f..3f, 50f..53f))
        assertEquals(listOf(GroupSegments.Segment(3f, 50f, 24f, 6f), GroupSegments.Segment(53f, 100f, 6f, 24f)), atTop)
        val atBottom = GroupSegments.of(100f, listOf(97f..100f))
        assertEquals(listOf(GroupSegments.Segment(0f, 97f, 24f, 24f)), atBottom)
    }

    @Test fun aGapOutsideTheGroupIsIgnoredAndOneThatSticksOutIsCut() {
        assertEquals(listOf(GroupSegments.Segment(0f, 100f, 24f, 24f)), GroupSegments.of(100f, listOf(120f..123f)))
        assertEquals(listOf(GroupSegments.Segment(0f, 90f, 24f, 24f)), GroupSegments.of(100f, listOf(90f..130f)))
    }

    @Test fun anEmptyGroupDrawsNothing() {
        assertTrue(GroupSegments.of(0f, emptyList()).isEmpty())
        assertTrue(GroupSegments.of(-4f, listOf(1f..2f)).isEmpty())
    }

    @Test fun theRadiiCanBeGivenInPixels() {
        val s = GroupSegments.of(200f, listOf(98f..101f), outer = 48f, inner = 12f)
        assertEquals(GroupSegments.Segment(0f, 98f, 48f, 12f), s[0])
        assertEquals(GroupSegments.Segment(101f, 200f, 12f, 48f), s[1])
    }

    @Test fun theCornersAreDrawnCircularAtTheRadiusOverOnePointThreeRounded() {
        assertEquals(18f, GroupSegments.drawnRadius(24f), 0f)
        assertEquals(5f, GroupSegments.drawnRadius(6f), 0f)
        assertEquals(0f, GroupSegments.drawnRadius(0f), 0f)
    }

    @Test fun theNumbersAreTheSpecs() {
        assertEquals(3f, GroupSegments.GAP_DP, 0f)
        assertEquals(24f, GroupSegments.OUTER_RADIUS_DP, 0f)
        assertEquals(6f, GroupSegments.INNER_RADIUS_DP, 0f)
    }
}
