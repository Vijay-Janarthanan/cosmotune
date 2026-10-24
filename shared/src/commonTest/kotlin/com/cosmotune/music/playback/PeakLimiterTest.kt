package com.cosmotune.music.playback

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared catch under the equaliser's half make-up, used by the phone's 16-bit path and the desktop equaliser. */
class PeakLimiterTest {
    private fun limiter(rate: Int = 48_000) = PeakLimiter().apply { configure(rate) }

    @Test fun signalUnderTheCeilingPassesUntouched() {
        val limiter = limiter()
        repeat(1_000) { assertEquals(1f, limiter.gainFor(0.9f)) }
    }

    @Test fun anOverIsCaughtOnItsFirstFrameAndNeverExceedsTheCeiling() {
        val limiter = limiter()
        val gain = limiter.gainFor(2f)
        assertEquals(PeakLimiter.CEILING / 2f, gain, 1e-6f)
        val random = Random(7)
        repeat(48_000) {
            val peak = random.nextFloat() * 3f
            assertTrue(peak * limiter.gainFor(peak) <= PeakLimiter.CEILING + 1e-6f)
        }
    }

    @Test fun releaseIsGradualAndCompleteWithinAboutASecond() {
        val limiter = limiter()
        limiter.gainFor(4f)
        var previous = limiter.gainFor(0.1f)
        assertTrue(previous < 0.3f, "released instantly ($previous)")
        repeat(4_800) { // 100 ms
            val next = limiter.gainFor(0.1f)
            assertTrue(next >= previous)
            previous = next
        }
        assertTrue(previous in 0.5f..0.95f, "one time constant should recover most but not all of it ($previous)")
        repeat(48_000) { previous = limiter.gainFor(0.1f) }
        assertTrue(abs(1f - previous) < 1e-3f)
    }

    @Test fun resetAndAnUnknownRateLetGoAtOnce() {
        val limiter = limiter()
        limiter.gainFor(3f)
        limiter.reset()
        assertEquals(1f, limiter.gainFor(0.5f))
        val unconfigured = limiter(rate = 0)
        unconfigured.gainFor(3f)
        assertEquals(1f, unconfigured.gainFor(0.5f))
    }

    @Test fun theCurveTakesBackHalfItsBoostAndNothingForACut() {
        assertEquals(0f, abs(EqCurve.FLAT.preampDb))
        assertEquals(0f, abs(manualCurve(List(EqLayout.MANUAL_COUNT) { -6f }).preampDb))
        val bass = manualCurve(listOf(12f) + List(EqLayout.MANUAL_COUNT - 1) { 0f })
        // A +12 dB low shelf peaks near +12.8 dB (its Q of 1 overshoots slightly); half of it is taken back, the limiter covers the rest.
        assertTrue(bass.preampDb in -7f..-5.5f, "preamp ${bass.preampDb}")
    }
}
