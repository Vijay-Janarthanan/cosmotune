package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** A settings slider is the thick 0..1 slider over a value range with Material's `steps`: `steps` stops between the two ends. */
class SliderStepsTest {
    private val crossfade = 0f..12f

    @Test fun elevenStepsOverZeroToTwelveSnapToWholeSeconds() {
        assertEquals(0f, SliderSteps.snap(0f, crossfade, 11), 0f)
        assertEquals(6f, SliderSteps.snap(0.5f, crossfade, 11), 0f)
        assertEquals(12f, SliderSteps.snap(1f, crossfade, 11), 0f)
        assertEquals(6f, SliderSteps.snap(0.54f, crossfade, 11), 0f)
        assertEquals(7f, SliderSteps.snap(0.55f, crossfade, 11), 0f)
        assertEquals(5f, SliderSteps.snap(0.41f, crossfade, 11), 0f)
    }

    @Test fun aValueIsExactAtEveryStop() {
        for (i in 0..12) assertEquals(i.toFloat(), SliderSteps.snap(i / 12f, crossfade, 11), 0f)
    }

    @Test fun nineteenStepsMakeTwentyOneStopsOverARangeThatDoesNotStartAtZero() {
        val cache = 500f..10_500f
        assertEquals(500f, SliderSteps.snap(0f, cache, 19), 0f)
        assertEquals(10_500f, SliderSteps.snap(1f, cache, 19), 0f)
        assertEquals(1_000f, SliderSteps.snap(0.05f, cache, 19), 0f)
        assertEquals(5_500f, SliderSteps.snap(0.5f, cache, 19), 0f)
        assertEquals(1_000f, SliderSteps.snap(0.06f, cache, 19), 0f)
        assertEquals(500f, SliderSteps.snap(0.024f, cache, 19), 0f)
    }

    @Test fun noStepsIsContinuous() {
        assertEquals(3f, SliderSteps.snap(0.25f, crossfade, 0), 1e-6f)
        assertEquals(3f, SliderSteps.snap(0.25f, crossfade, -2), 1e-6f)
    }

    @Test fun oneStepHasAMiddleStopAndTheTieGoesUp() {
        assertEquals(0f, SliderSteps.snap(0.24f, 0f..10f, 1), 0f)
        assertEquals(5f, SliderSteps.snap(0.25f, 0f..10f, 1), 0f)
        assertEquals(5f, SliderSteps.snap(0.5f, 0f..10f, 1), 0f)
        assertEquals(10f, SliderSteps.snap(0.75f, 0f..10f, 1), 0f)
    }

    @Test fun aFractionOutsideZeroToOneIsHeldAtTheEnds() {
        assertEquals(0f, SliderSteps.snap(-0.3f, crossfade, 11), 0f)
        assertEquals(12f, SliderSteps.snap(1.7f, crossfade, 11), 0f)
        assertEquals(0f, SliderSteps.snap(Float.NaN, crossfade, 11), 0f)
    }

    @Test fun anEmptyRangeIsItsOnlyValue() {
        assertEquals(4f, SliderSteps.snap(0.7f, 4f..4f, 5), 0f)
        assertEquals(0f, SliderSteps.fractionOf(4f, 4f..4f), 0f)
    }

    @Test fun theFractionOfAValueIsItsShareOfTheRangeHeldBetweenZeroAndOne() {
        assertEquals(0.5f, SliderSteps.fractionOf(6f, crossfade), 1e-6f)
        assertEquals(0f, SliderSteps.fractionOf(-3f, crossfade), 0f)
        assertEquals(1f, SliderSteps.fractionOf(20f, crossfade), 0f)
        assertEquals(0.25f, SliderSteps.fractionOf(3_000f, 500f..10_500f), 0.001f)
    }

    @Test fun snappingTheFractionOfAStopGivesTheStopBack() {
        val cache = 500f..10_500f
        for (i in 0..20) {
            val value = 500f + 500f * i
            assertEquals(value, SliderSteps.snap(SliderSteps.fractionOf(value, cache), cache, 19), 0f)
        }
    }
}
