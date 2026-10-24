package com.cosmotune.music.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The crown/bezel, hold-to-seek and always-on rules of the watch's Now Playing screen. */
class WatchControlsTest {

    @Test
    fun `a notched bezel detent is exactly one step either way`() {
        val detents = RotaryDetents(48f)
        assertEquals(1, detents.steps(120f))
        assertEquals(-1, detents.steps(-300f))
        assertEquals(1, detents.steps(48f))
    }

    @Test
    fun `a smooth crown adds small events up into whole steps`() {
        val detents = RotaryDetents(48f)
        val steps = List(10) { detents.steps(10f) }
        // 100px of travel is two steps; the remaining 4px wait for more.
        assertEquals(2, steps.sum())
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 1), steps)
        assertEquals(1, detents.steps(44f))
    }

    @Test
    fun `turning back cancels pending travel before stepping down`() {
        val detents = RotaryDetents(48f)
        assertEquals(0, detents.steps(40f))
        assertEquals(0, detents.steps(-40f))
        assertEquals(0, detents.steps(-40f))
        assertEquals(-1, detents.steps(-10f))
    }

    @Test
    fun `a detent drops the partial travel left from the crown`() {
        val detents = RotaryDetents(48f)
        assertEquals(0, detents.steps(40f))
        assertEquals(-1, detents.steps(-60f))
        // Without the reset, 40px pending would have made this a step.
        assertEquals(0, detents.steps(10f))
    }

    @Test
    fun `no travel or a broken reading is no step`() {
        val detents = RotaryDetents(48f)
        assertEquals(0, detents.steps(0f))
        assertEquals(0, detents.steps(Float.NaN))
        assertEquals(0, detents.steps(47f))
        assertEquals(1, detents.steps(1f))
    }

    @Test
    fun `hold to seek stays inside the track`() {
        assertEquals(65_000L, scrubTarget(60_000L, 5_000L, 200_000L))
        assertEquals(0L, scrubTarget(3_000L, -5_000L, 200_000L))
        assertEquals(200_000L, scrubTarget(198_000L, 5_000L, 200_000L))
        // An unknown duration pins the target at the start rather than running off.
        assertEquals(0L, scrubTarget(10_000L, 5_000L, -1L))
    }

    @Test
    fun `the always-on player orbits within 6dp and only when the panel asks`() {
        assertEquals(0 to 0, burnInShiftDp(5, burnInProtection = false))
        val orbit = (0 until 8).map { burnInShiftDp(it, burnInProtection = true) }
        assertEquals(8, orbit.toSet().size)
        orbit.forEach { (x, y) -> assertTrue("$x,$y", abs(x) <= 6 && abs(y) <= 6) }
        // Each minute moves a few dp at most, so the text never jumps.
        (0 until 8).forEach { i ->
            val (x1, y1) = orbit[i]
            val (x2, y2) = orbit[(i + 1) % 8]
            assertTrue(abs(x2 - x1) <= 4 && abs(y2 - y1) <= 4)
        }
        assertEquals(orbit[0], burnInShiftDp(8, burnInProtection = true))
        assertEquals(orbit[7], burnInShiftDp(-1, burnInProtection = true))
    }
}
