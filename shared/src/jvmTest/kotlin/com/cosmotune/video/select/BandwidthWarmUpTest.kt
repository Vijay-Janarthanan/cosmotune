package com.cosmotune.video.select

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The first 5 s of measuring (spec 6.4: the bandwidth rule applies "after the first 5 s; before that, the setting alone"). */
class BandwidthWarmUpTest {
    private var now = 1_000_000L
    private val warmUp = BandwidthWarmUp(clock = { now })

    @Test fun nothingIsReportedForTheFirstFiveSecondsOfMeasuring() {
        warmUp.restart()
        assertNull(warmUp.gate(4_000))
        now += 4_999
        assertNull(warmUp.gate(4_000))
        now += 1
        assertEquals(4_000, warmUp.gate(4_000))
        assertEquals(BandwidthWarmUp.WARM_UP_MS, 5_000L)
    }

    @Test fun measuringStartsAtTheFirstValueWhenNothingStartedIt() {
        assertNull(warmUp.gate(2_500))
        now += 5_000
        assertEquals(2_500, warmUp.gate(2_500))
    }

    @Test fun beginStartsOnlyOnceButRestartStartsAgain() {
        warmUp.begin()
        now += 3_000
        warmUp.begin()
        now += 2_000
        assertEquals(1_200, warmUp.gate(1_200), "the second begin did not move the start")
        warmUp.restart()
        assertNull(warmUp.gate(1_200), "a new meter measures from zero")
    }

    @Test fun anUnknownOrEmptyValueIsUnknown() {
        warmUp.restart()
        now += 10_000
        assertNull(warmUp.gate(null))
        assertNull(warmUp.gate(0))
        assertNull(warmUp.gate(-5))
    }
}
