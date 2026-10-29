package com.cosmotune.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop picture-in-picture window's geometry. */
class PipWindowMathTest {

    private val wide = 16f / 9f
    private val screen = PipBounds(0, 0, 1920, 1080)

    @Test
    fun theHeightFollowsTheVideosShape() {
        assertEquals(270, PipWindowMath.heightFor(480, wide))
        assertEquals(360, PipWindowMath.heightFor(480, 4f / 3f))
        assertEquals(270, PipWindowMath.heightFor(480, 0f), "an unknown shape is 16:9")
    }

    @Test
    fun itOpensInTheBottomRightCornerOfTheScreen() {
        val bounds = PipWindowMath.initial(wide, screen)
        assertEquals(PipBounds(1920 - 480 - 24, 1080 - 270 - 24, 480, 270), bounds)
    }

    @Test
    fun aSmallScreenGetsASmallerWindowButNeverBelowTheMinimum() {
        assertEquals(PipWindowMath.MIN_WIDTH, PipWindowMath.initial(wide, PipBounds(0, 0, 600, 400)).width)
        assertEquals(PipWindowMath.MIN_WIDTH, PipWindowMath.initial(wide, PipBounds(0, 0, 300, 300)).width)
    }

    @Test
    fun draggingTheBottomRightCornerGrowsTheWindowFromItsTopLeftKeepingTheShape() {
        val start = PipBounds(100, 100, 480, 270)
        val next = PipWindowMath.resized(start, PipCorner.BOTTOM_RIGHT, dx = 96, dy = 0, aspect = wide, maxWidth = 1920)
        assertEquals(PipBounds(100, 100, 576, 324), next)
    }

    @Test
    fun draggingTheTopLeftCornerKeepsTheBottomRightCornerStill() {
        val start = PipBounds(100, 100, 480, 270)
        val next = PipWindowMath.resized(start, PipCorner.TOP_LEFT, dx = -96, dy = 0, aspect = wide, maxWidth = 1920)
        assertEquals(576, next.width)
        assertEquals(start.right, next.right)
        assertEquals(start.bottom, next.bottom)
    }

    @Test
    fun aDragMostlyUpOrDownResizesByTheHeightItImplies() {
        val start = PipBounds(100, 100, 480, 270)
        val next = PipWindowMath.resized(start, PipCorner.BOTTOM_RIGHT, dx = 0, dy = 90, aspect = wide, maxWidth = 1920)
        assertEquals(360, next.height)
        assertEquals(640, next.width)
    }

    @Test
    fun theWidthStaysBetweenTheMinimumAndTheLimit() {
        val start = PipBounds(100, 100, 480, 270)
        assertEquals(PipWindowMath.MIN_WIDTH, PipWindowMath.resized(start, PipCorner.BOTTOM_RIGHT, -900, 0, wide, 1920).width)
        assertEquals(1000, PipWindowMath.resized(start, PipCorner.BOTTOM_RIGHT, 900, 0, wide, 1000).width)
    }

    @Test
    fun aNewVideoShapeKeepsTheWidthAndTheCorner() {
        val next = PipWindowMath.reshaped(PipBounds(10, 20, 480, 270), 4f / 3f)
        assertEquals(PipBounds(10, 20, 480, 360), next)
    }

    @Test
    fun aWindowOffTheScreenIsBroughtBackToTheScreenItOverlapsMost() {
        val second = PipBounds(1920, 0, 1280, 720)
        val screens = listOf(screen, second)
        assertEquals(PipBounds(1440, 810, 480, 270), PipWindowMath.onScreen(PipBounds(1800, 1000, 480, 270), screens))
        assertEquals(PipBounds(2000, 100, 480, 270), PipWindowMath.onScreen(PipBounds(2000, 100, 480, 270), screens))
        // a monitor that is gone: nothing overlaps, the first screen takes it
        assertEquals(PipBounds(1440, 0, 480, 270), PipWindowMath.onScreen(PipBounds(5000, -400, 480, 270), listOf(screen)))
    }

    @Test
    fun aWindowLargerThanTheScreenIsMadeToFit() {
        assertEquals(PipBounds(0, 0, 1920, 1080), PipWindowMath.onScreen(PipBounds(-50, -50, 3000, 2000), listOf(screen)))
    }

    @Test
    fun theBoundsRoundTripAsTextAndBadTextIsNotBounds() {
        val bounds = PipBounds(-30, 40, 500, 281)
        assertEquals(bounds, PipWindowMath.decode(PipWindowMath.encode(bounds)))
        assertNull(PipWindowMath.decode(""))
        assertNull(PipWindowMath.decode("1,2,3"))
        assertNull(PipWindowMath.decode("1,2,x,4"))
        assertNull(PipWindowMath.decode("1,2,100,100"), "narrower than the minimum")
    }
}
