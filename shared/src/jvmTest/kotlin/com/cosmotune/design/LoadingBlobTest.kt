package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The loading shape of the B+C spec: a blob that becomes a circle half way through and turns once, in 1.1 s. */
class LoadingBlobTest {
    private fun at(phase: Float) = FloatArray(LoadingBlob.SIZE).also { LoadingBlob.at(phase, it) }

    @Test
    fun atTheStartItIsTheBlobOfTheCssKeyframe() {
        val s = at(0f)
        assertEquals(0f, s[LoadingBlob.ROTATION_DEG], 0.001f)
        assertEquals(0.9f, s[LoadingBlob.SCALE], 0.001f)
        // border-radius: 30% 70% 62% 38% / 38% 34% 66% 62%
        val expected = listOf(0.30f, 0.70f, 0.62f, 0.38f, 0.38f, 0.34f, 0.66f, 0.62f)
        for (i in 0 until 8) assertEquals(expected[i], s[LoadingBlob.RADII + i], 0.001f, "radius $i")
    }

    @Test
    fun inTheMiddleItIsACircleAtFullSizeAHalfTurnOn() {
        val s = at(0.5f)
        assertEquals(180f, s[LoadingBlob.ROTATION_DEG], 0.001f)
        assertEquals(1.05f, s[LoadingBlob.SCALE], 0.001f)
        for (i in 0 until 8) assertEquals(0.5f, s[LoadingBlob.RADII + i], 0.001f, "radius $i")
    }

    @Test
    fun atTheEndItHasTurnedOnceAndIsTheBlobAgain() {
        val start = at(0f)
        val end = at(1f)
        assertEquals(360f, end[LoadingBlob.ROTATION_DEG], 0.001f)
        for (i in 1 until LoadingBlob.SIZE) assertEquals(start[i], end[i], 0.001f, "slot $i")
    }

    @Test
    fun theTurnNeverRunsBackwardsAndTheRadiiOfNeighbouringCornersAddUpToTheSide() {
        var last = -1f
        for (i in 0..40) {
            val s = at(i / 40f)
            assertEquals(true, s[LoadingBlob.ROTATION_DEG] >= last, "rotation at $i")
            last = s[LoadingBlob.ROTATION_DEG]
            val r = LoadingBlob.RADII
            // horizontal radii: top-left, top-right, bottom-right, bottom-left; vertical: the same order
            assertEquals(1f, s[r] + s[r + 1], 0.001f, "top at $i"); assertEquals(1f, s[r + 2] + s[r + 3], 0.001f, "bottom at $i")
            assertEquals(1f, s[r + 4] + s[r + 7], 0.001f, "left at $i"); assertEquals(1f, s[r + 5] + s[r + 6], 0.001f, "right at $i")
        }
    }

    @Test
    fun aPhaseOutsideTheLoopWrapsAround() {
        val a = at(0.25f)
        val b = at(1.25f)
        for (i in 0 until LoadingBlob.SIZE) assertEquals(a[i], b[i], 0.001f, "slot $i")
    }
}
