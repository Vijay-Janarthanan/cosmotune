package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pull-to-refresh blob of the B+C spec (`.ptr` in `components-v3bc.css`): `.p30` and `.p70` and the line between them. */
class PullIndicatorTest {
    @Test
    fun atThirtyPercentItIsTheP30FrameOfTheBoard() {
        assertEquals(0.55f, PullIndicator.scale(0.3f), 0.0001f)
        assertEquals(30f, PullIndicator.rotationDeg(0.3f), 0.0001f)
    }

    @Test
    fun atSeventyPercentItIsTheP70FrameOfTheBoard() {
        assertEquals(0.85f, PullIndicator.scale(0.7f), 0.0001f)
        assertEquals(120f, PullIndicator.rotationDeg(0.7f), 0.0001f)
    }

    @Test
    fun betweenThemItGrowsAndTurnsInALine() {
        assertEquals(0.70f, PullIndicator.scale(0.5f), 0.0001f)
        assertEquals(75f, PullIndicator.rotationDeg(0.5f), 0.0001f)
        assertEquals(0.625f, PullIndicator.scale(0.4f), 0.0001f)
        assertEquals(52.5f, PullIndicator.rotationDeg(0.4f), 0.0001f)
        assertEquals(0.775f, PullIndicator.scale(0.6f), 0.0001f)
        assertEquals(97.5f, PullIndicator.rotationDeg(0.6f), 0.0001f)
    }

    @Test
    fun belowThirtyPercentItHoldsTheFirstFrame() {
        for (p in listOf(0f, 0.1f, 0.29f, -0.5f, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.55f, PullIndicator.scale(p), 0.0001f, "scale at $p")
            assertEquals(30f, PullIndicator.rotationDeg(p), 0.0001f, "rotation at $p")
            assertEquals(0.5f, PullIndicator.cornerFraction(p), 0.0001f, "corner at $p")
        }
    }

    @Test
    fun aboveSeventyPercentItHoldsTheLastFrameEvenWhenThePullOvershoots() {
        for (p in listOf(0.71f, 1f, 1.8f, 2f, Float.POSITIVE_INFINITY)) {
            assertEquals(0.85f, PullIndicator.scale(p), 0.0001f, "scale at $p")
            assertEquals(120f, PullIndicator.rotationDeg(p), 0.0001f, "rotation at $p")
            assertEquals(0.3f, PullIndicator.cornerFraction(p), 0.0001f, "corner at $p")
        }
    }

    @Test
    fun theCornersGoFromRoundToThirtyPercentOverTheSameStretch() {
        assertEquals(0.5f, PullIndicator.cornerFraction(0.3f), 0.0001f)
        assertEquals(0.4f, PullIndicator.cornerFraction(0.5f), 0.0001f)
        assertEquals(0.3f, PullIndicator.cornerFraction(0.7f), 0.0001f)
    }

    @Test
    fun nothingMovesBackwardsAsThePullGrows() {
        var scale = 0f
        var rotation = 0f
        var corner = 1f
        for (i in 0..200) {
            val p = i / 100f
            assertTrue(PullIndicator.scale(p) >= scale, "scale at $p")
            assertTrue(PullIndicator.rotationDeg(p) >= rotation, "rotation at $p")
            assertTrue(PullIndicator.cornerFraction(p) <= corner, "corner at $p")
            scale = PullIndicator.scale(p)
            rotation = PullIndicator.rotationDeg(p)
            corner = PullIndicator.cornerFraction(p)
        }
    }

    @Test
    fun aNotANumberPullIsTheFirstFrameNotAnException() {
        assertEquals(0.55f, PullIndicator.scale(Float.NaN), 0.0001f)
        assertEquals(30f, PullIndicator.rotationDeg(Float.NaN), 0.0001f)
        assertEquals(0.5f, PullIndicator.cornerFraction(Float.NaN), 0.0001f)
    }

    @Test
    fun theCircleHas44dpAnd18dpBlobAndTheTravelOfTheBoard() {
        assertEquals(44f, PullIndicator.SIZE_DP)
        assertEquals(18f, PullIndicator.BLOB_DP)
        assertEquals(64f, PullIndicator.TRAVEL_DP)
    }
}
