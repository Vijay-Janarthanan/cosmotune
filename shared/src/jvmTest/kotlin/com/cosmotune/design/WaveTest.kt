package com.cosmotune.design

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The wavy scrubber's played stretch (`v2.js` `V2.drawWaves`, scaled to dp). */
class WaveTest {

    private class Result(val out: FloatArray, val count: Int)

    private fun points(
        width: Float = 340f,
        progress: Float = 0.6f,
        amplitude: Float = Wave.AMPLITUDE_DP,
        wavelength: Float = Wave.WAVELENGTH_DP,
        phase: Float = 0f,
        midY: Float = 14f,
        startX: Float = 2f,
        endGap: Float = Wave.GAP_BEFORE_DP,
        step: Float = Wave.STEP_DP,
        capacity: Int = Wave.pointCapacity(width, step),
    ): Result {
        val out = FloatArray(2 * capacity)
        return Result(out, Wave.playedPoints(width, progress, amplitude, wavelength, phase, midY, startX, endGap, step, out))
    }

    @Test
    fun theDpConstantsAreTheDesignersValues() {
        assertEquals(28f, Wave.HEIGHT_DP)
        assertEquals(3.2f, Wave.AMPLITUDE_DP)
        assertEquals(26f, Wave.WAVELENGTH_DP)
        assertEquals(4f, Wave.STROKE_DP)
        assertEquals(5f, Wave.GAP_BEFORE_DP)
        assertEquals(7f, Wave.GAP_AFTER_DP)
        assertEquals(2f, Wave.STEP_DP)
        assertEquals(4f, Wave.HANDLE_W_DP)
        assertEquals(24f, Wave.HANDLE_H_DP)
    }

    @Test
    fun zeroAmplitudeIsAStraightLineAtMidY() {
        val r = points(amplitude = 0f, midY = 14f)
        assertTrue(r.count > 0)
        for (i in 0 until r.count) assertEquals(14f, r.out[2 * i + 1], 1e-6f, "point $i")
    }

    @Test
    fun theLastPointIsNoFurtherThanTheProgressMinusTheGap() {
        for (progress in floatArrayOf(0.05f, 0.3f, 0.5f, 0.77f, 1f)) {
            val r = points(progress = progress)
            assertTrue(r.count > 0, "progress $progress")
            assertTrue(r.out[2 * (r.count - 1)] <= progress * 340f - Wave.GAP_BEFORE_DP + 1e-4f, "progress $progress")
        }
    }

    @Test
    fun theWaveStartsAtStartXAndStepsEvenly() {
        val r = points()
        assertEquals(2f, r.out[0], 1e-6f)
        for (i in 1 until r.count) assertEquals(Wave.STEP_DP, r.out[2 * i] - r.out[2 * (i - 1)], 1e-3f, "step $i")
    }

    @Test
    fun yFollowsTheSineAtEachX() {
        val r = points(phase = 0.7f)
        for (i in 0 until r.count) {
            val x = r.out[2 * i]
            val expected = 14f + Wave.AMPLITUDE_DP * sin((2.0 * PI * x / Wave.WAVELENGTH_DP + 0.7).toFloat())
            assertEquals(expected, r.out[2 * i + 1], 1e-3f, "point $i")
        }
    }

    @Test
    fun progressZeroGivesNoPoints() {
        assertEquals(0, points(progress = 0f).count)
    }

    @Test
    fun aVeryShortPlayedStretchGivesNoPoints() {
        // 1 % of 340 is 3.4, less the 5 dp gap: nothing to draw before the handle.
        assertEquals(0, points(progress = 0.01f).count)
    }

    @Test
    fun theSuggestedCapacityIsNeverExceededAtFullProgress() {
        for (width in floatArrayOf(120f, 340f, 341.3f, 640f, 1000f)) {
            val capacity = ceil(width / Wave.STEP_DP).toInt() + 2
            assertEquals(capacity, Wave.pointCapacity(width, Wave.STEP_DP))
            val r = points(width = width, progress = 1f, capacity = capacity)
            assertTrue(r.count <= capacity, "width $width gave ${r.count} points for $capacity")
        }
    }

    @Test
    fun aFullBufferStopsTheWriteAndReturnsItsCapacity() {
        assertEquals(10, points(progress = 1f, capacity = 10).count)
    }

    @Test
    fun anEmptyBufferGivesNoPoints() {
        assertEquals(0, points(progress = 1f, capacity = 0).count)
    }
}
