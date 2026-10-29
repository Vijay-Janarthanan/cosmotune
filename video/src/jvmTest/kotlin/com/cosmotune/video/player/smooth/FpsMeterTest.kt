package com.cosmotune.video.player.smooth

import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessReadout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frame-rate measure reads the cadence of the frames that arrive, and says nothing it does not know. */
class FpsMeterTest {
    private val second = 1_000_000_000L

    /** Frame timestamps of a [fps] video, [count] frames, with [jitterNanos] of deterministic wobble. */
    private fun stamps(fps: Double, count: Int, jitterNanos: Long = 0, start: Long = 5 * second): List<Long> =
        (0 until count).map { i -> start + (i * second / fps).toLong() + if (i % 3 == 0) jitterNanos else if (i % 3 == 1) -jitterNanos else 0 }

    @Test
    fun measures23976FromItsFrameTimes() {
        val meter = FpsMeter()
        val times = stamps(24000.0 / 1001.0, 40)
        times.forEach(meter::tick)
        val fps = assertNotNull(meter.fps(times.last()))
        assertTrue(abs(fps - 23.976f) < 0.05f, "measured $fps")
        assertEquals("24", SmoothnessReadout.fpsText(fps))
    }

    @Test
    fun jitterOfAFewMillisecondsDoesNotMoveTheMeasure() {
        val meter = FpsMeter()
        val times = stamps(25.0, 60, jitterNanos = 4_000_000)
        times.forEach(meter::tick)
        val fps = assertNotNull(meter.fps(times.last()))
        assertTrue(abs(fps - 25f) < 0.6f, "measured $fps")
    }

    @Test
    fun anUnknownRateIsNull() {
        val meter = FpsMeter()
        assertNull(meter.fps(0))
        val times = stamps(30.0, FpsMeter.MIN_GAPS) // one frame fewer than a measure needs
        times.forEach(meter::tick)
        assertNull(meter.fps(times.last()), "not enough frames yet")
        meter.tick(times.last() + second / 30)
        assertNotNull(meter.fps(times.last() + second / 30))
    }

    @Test
    fun aStoppedVideoHasNoRateAnyMore() {
        val meter = FpsMeter()
        val times = stamps(24.0, 30)
        times.forEach(meter::tick)
        assertNotNull(meter.fps(times.last() + second / 2))
        assertNull(meter.fps(times.last() + 2 * second), "paused for two seconds")
    }

    @Test
    fun aLongStallStartsTheMeasureAgain() {
        val meter = FpsMeter()
        val first = stamps(24.0, 30)
        first.forEach(meter::tick)
        val resume = first.last() + 5 * second
        meter.tick(resume)
        assertNull(meter.fps(resume), "the stretch after a stall is measured afresh")
        val later = stamps(30.0, 20, start = resume + second / 30)
        later.forEach(meter::tick)
        val fps = assertNotNull(meter.fps(later.last()))
        assertTrue(abs(fps - 30f) < 0.5f, "measured $fps")
    }

    @Test
    fun theSameFrameDrawnTwiceIsNotAFrame() {
        val meter = FpsMeter()
        val times = stamps(24.0, 30)
        for (t in times) {
            meter.tick(t)
            meter.tick(t + 200_000) // a redraw at a new size 0.2 ms later
        }
        val fps = assertNotNull(meter.fps(times.last()))
        assertTrue(abs(fps - 24f) < 0.2f, "measured $fps")
    }

    @Test
    fun frameRatesAreWrittenTheWayPeopleWriteThem() {
        assertEquals("24", SmoothnessReadout.fpsText(23.976f))
        assertEquals("30", SmoothnessReadout.fpsText(29.97f))
        assertEquals("60", SmoothnessReadout.fpsText(59.94f))
        assertEquals("25", SmoothnessReadout.fpsText(25f))
        assertEquals("24.5", SmoothnessReadout.fpsText(24.5f))
        assertEquals("12.3", SmoothnessReadout.fpsText(12.3f))
    }

    @Test
    fun theLabelNeverSaysAConversionThatIsNotHappening() {
        assertEquals("24 → 60 fps · Smooth", SmoothnessReadout(SmoothMode.SMOOTH, 23.976f, 60f).label())
        assertEquals("25 → 120 fps · Blend", SmoothnessReadout(SmoothMode.BLEND, 25f, 120f).label())
        assertNull(SmoothnessReadout(SmoothMode.OFF, 24f, 60f).label(), "off")
        assertNull(SmoothnessReadout(SmoothMode.SMOOTH, null, 60f).label(), "the video's rate is not known")
        assertNull(SmoothnessReadout(SmoothMode.SMOOTH, 24f, null).label(), "the drawn rate is not known")
        assertNull(SmoothnessReadout(SmoothMode.SMOOTH, 24f, 24f).label(), "drawn at the source's own rate: nothing is made")
    }
}
