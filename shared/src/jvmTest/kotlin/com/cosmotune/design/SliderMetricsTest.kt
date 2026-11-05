package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The thick slider's geometry (`components-v3bc.css` `.sl2`): a 16 dp track in two pieces round a 4 dp handle with 6 dp gaps. */
class SliderMetricsTest {
    private val half = SliderMetrics.HALF_SLOT_DP

    @Test fun theHandleSlotIsEightDpEachSide() {
        assertEquals(8f, half, 0f)
    }

    @Test fun aPointerMapsToItsShareOfTheWidthAndIsClamped() {
        assertEquals(0.25f, SliderMetrics.valueAt(50f, 200f), 1e-6f)
        assertEquals(0f, SliderMetrics.valueAt(-30f, 200f), 0f)
        assertEquals(1f, SliderMetrics.valueAt(260f, 200f), 0f)
        assertEquals(0f, SliderMetrics.valueAt(10f, 0f), 0f)
    }

    @Test fun theHandleFollowsTheValueButStaysWholeInsideTheBar() {
        assertEquals(100f, SliderMetrics.handleCentre(0.5f, 200f, half), 1e-4f)
        assertEquals(half, SliderMetrics.handleCentre(0f, 200f, half), 1e-4f)
        assertEquals(200f - half, SliderMetrics.handleCentre(1f, 200f, half), 1e-4f)
        assertEquals(half, SliderMetrics.handleCentre(-1f, 200f, half), 1e-4f)
        assertEquals(200f - half, SliderMetrics.handleCentre(2f, 200f, half), 1e-4f)
    }

    @Test fun theTwoTracksAndTheHandleSlotFillTheWidthExactly() {
        for (value in listOf(0f, 0.03f, 0.37f, 0.5f, 0.99f, 1f)) {
            val width = 280f
            val c = SliderMetrics.handleCentre(value, width, half)
            val active = SliderMetrics.activeEnd(c, half)
            val inactive = width - SliderMetrics.inactiveStart(c, half)
            assertTrue(active >= 0f && inactive >= 0f, "value $value: active $active, inactive $inactive")
            assertEquals(width, active + 2 * half + inactive, 1e-3f, "value $value")
        }
    }

    @Test fun aBarTooNarrowForTheHandleDoesNotGoNegative() {
        val c = SliderMetrics.handleCentre(0.5f, 10f, half)
        assertTrue(SliderMetrics.activeEnd(c, half) >= 0f)
        assertTrue(c >= half)
    }

    @Test fun tenthsTickOncePerTenPercent() {
        assertEquals(0, SliderMetrics.tickOf(0f))
        assertEquals(0, SliderMetrics.tickOf(0.099f))
        assertEquals(1, SliderMetrics.tickOf(0.1f))
        assertEquals(5, SliderMetrics.tickOf(0.55f))
        assertEquals(10, SliderMetrics.tickOf(1f))
    }

    @Test fun keysStepByOnePercentAndTenWithTheBigStepAndStopAtTheEnds() {
        assertEquals(0.51f, SliderMetrics.stepped(0.5f, direction = 1, big = false), 1e-6f)
        assertEquals(0.49f, SliderMetrics.stepped(0.5f, direction = -1, big = false), 1e-6f)
        assertEquals(0.60f, SliderMetrics.stepped(0.5f, direction = 1, big = true), 1e-6f)
        assertEquals(1f, SliderMetrics.stepped(0.995f, direction = 1, big = false), 0f)
        assertEquals(0f, SliderMetrics.stepped(0.004f, direction = -1, big = true), 0f)
    }

    @Test fun theTouchHeightIsAtLeastFortyEightDp() {
        assertTrue(SliderMetrics.touchHeightDp(SliderMetrics.HEIGHT_DP) >= 48f)
        assertTrue(SliderMetrics.touchHeightDp(Wave.HEIGHT_DP) >= 48f)
        assertEquals(60f, SliderMetrics.touchHeightDp(60f), 0f)
    }
}
