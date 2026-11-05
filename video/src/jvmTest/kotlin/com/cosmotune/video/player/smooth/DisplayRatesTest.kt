package com.cosmotune.video.player.smooth

import com.cosmotune.video.settings.SmoothnessSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What rate the phone's screen can be drawn at, and when a faster one is asked for. */
class DisplayRatesTest {
    private val modes = listOf(
        DisplayRates.Mode(1080, 2400, 60f),
        DisplayRates.Mode(1080, 2400, 120f),
        DisplayRates.Mode(1440, 3200, 144f),
    )

    @Test
    fun aScreenThatCannotBeAskedStaysWhereItIs() {
        assertEquals(60f, DisplayRates.ceilingHz(60f, modes[0], modes, canRequest = false))
    }

    @Test
    fun aScreenThatCanBeAskedGoesToItsFastestModeAtTheSameSize() {
        assertEquals(120f, DisplayRates.ceilingHz(60f, modes[0], modes, canRequest = true))
        // the 144 Hz mode is another size: switching to it would resize the screen
        assertEquals(120f, DisplayRates.ceilingHz(120f, modes[1], modes, canRequest = true))
    }

    @Test
    fun anUnknownRateIsSixty() {
        assertEquals(60f, DisplayRates.ceilingHz(0f, null, emptyList(), canRequest = true))
    }

    @Test
    fun followingTheDisplayUsesTheRateItRunsAt() {
        val s = SmoothnessSettings(targetFps = 0)
        assertEquals(60f, DisplayRates.displayHzFor(s, 60f, 120f))
        assertEquals(120f, DisplayRates.displayHzFor(s.copy(targetFps = 90), 60f, 120f))
    }

    @Test
    fun aTargetAboveTheCurrentRateIsAskedForUpToTheCeiling() {
        val s = SmoothnessSettings(targetFps = 90)
        assertEquals(90f, DisplayRates.requestHz(s, 60f, 120f))
        assertEquals(120f, DisplayRates.requestHz(s.copy(targetFps = 120), 60f, 120f))
        assertEquals(90f, DisplayRates.requestHz(s.copy(targetFps = 120), 60f, 90f))
    }

    @Test
    fun nothingIsAskedWhenTheScreenAlreadyRunsThere() {
        assertNull(DisplayRates.requestHz(SmoothnessSettings(targetFps = 60), 60f, 120f))
        assertNull(DisplayRates.requestHz(SmoothnessSettings(targetFps = 90), 90f, 120f))
        assertNull(DisplayRates.requestHz(SmoothnessSettings(targetFps = 0), 60f, 120f))
        assertNull(DisplayRates.requestHz(SmoothnessSettings(targetFps = 120), 60f, 60f))
    }

    @Test
    fun theOutputRateIsCappedAtTheCeiling() {
        // the policy's own cap, fed the display rate chosen above
        val s = SmoothnessSettings(targetFps = 120)
        assertEquals(60f, SmoothPolicy.outputFps(s, DisplayRates.displayHzFor(s, 60f, 60f)))
        assertEquals(120f, SmoothPolicy.outputFps(s, DisplayRates.displayHzFor(s, 60f, 120f)))
    }

    @Test
    fun aRefreshIsAboutSixteenMilliseconds() {
        assertEquals(16_666_666L, DisplayRates.periodNanos(60f))
        assertTrue(DisplayRates.same(59.94f, 60f))
    }
}
