package com.cosmotune.video.player.smooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which refresh rate frame smoothing takes: the monitor of the window the video is in, the rounded rates Windows reports, unknown. */
class DisplayRateTest {
    @Test
    fun aRoundedRateIsTheRateItStandsFor() {
        assertEquals(60f, DisplayRate.fromReported(59), "59.94 Hz")
        assertEquals(144f, DisplayRate.fromReported(143), "143.98 Hz")
        assertEquals(120f, DisplayRate.fromReported(119), "119.88 Hz")
        assertEquals(30f, DisplayRate.fromReported(29))
        assertEquals(24f, DisplayRate.fromReported(23))
        assertEquals(240f, DisplayRate.fromReported(239))
    }

    @Test
    fun anExactRateIsKeptAsItIs() {
        assertEquals(60f, DisplayRate.fromReported(60))
        assertEquals(144f, DisplayRate.fromReported(144))
        assertEquals(165f, DisplayRate.fromReported(165))
        assertEquals(75f, DisplayRate.fromReported(75))
        assertEquals(85f, DisplayRate.fromReported(85), "a rate that is no common one is believed")
    }

    @Test
    fun anUnknownOrAbsurdRateIsNoRate() {
        assertNull(DisplayRate.fromReported(0), "REFRESH_RATE_UNKNOWN")
        assertNull(DisplayRate.fromReported(-1))
        assertNull(DisplayRate.fromReported(5_000))
    }

    @Test
    fun anUnknownRateKeepsTheLastKnownOneThenSixty() {
        assertEquals(144f, DisplayRate.choose(0, 144f))
        assertEquals(60f, DisplayRate.choose(0, null))
        assertEquals(165f, DisplayRate.choose(165, 60f))
    }

    @Test
    fun theSourceReadsTheMonitorOfTheWindowThatIsDrawingAndFollowsItAcrossMonitors() {
        val rates = mapOf<Any?, Int>("main" to 144, "pip" to 59, null to 60)
        val source = DisplayRateSource { host -> rates.getValue(host) }
        assertEquals(60f, source.hz(), "no window known: the primary monitor")
        val page = Any()
        val pip = Any()
        source.attach(page, "main")
        assertEquals(144f, source.hz())
        source.attach(pip, "pip") // the picture-in-picture window takes the picture over
        assertEquals(60f, source.hz(), "59 is 59.94 Hz")
        source.detach(pip) // and gives it back
        assertEquals(144f, source.hz())
        source.detach(page)
        assertEquals(60f, source.hz())
    }

    @Test
    fun aMonitorThatCannotSayItsRateKeepsTheLastOne() {
        var reported = 144
        val source = DisplayRateSource { reported }
        source.attach("a", "main")
        assertEquals(144f, source.hz())
        reported = 0
        assertEquals(144f, source.hz())
    }

    @Test
    fun aSurfaceThatMovesToAnotherWindowIsAskedForTheNewOne() {
        val rates = mapOf<Any?, Int>("left" to 60, "right" to 165)
        val source = DisplayRateSource { host -> rates.getValue(host) }
        source.attach("surface", "left")
        assertEquals(60f, source.hz())
        source.attach("surface", "right")
        assertEquals(165f, source.hz())
    }
}
