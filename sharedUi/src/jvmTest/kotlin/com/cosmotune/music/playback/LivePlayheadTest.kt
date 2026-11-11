package com.cosmotune.music.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring and moon are drawn from a playhead that runs on from the last reading on the frame clock: it moves smoothly while
 * playing, holds still when paused, and a seek lands at once instead of sweeping.
 */
class LivePlayheadTest {
    private val ms = 1_000_000L

    private fun playing(position: PlaybackPosition = PlaybackPosition()): LivePlayhead =
        LivePlayhead(position).also { it.running = true }

    @Test fun itAdvancesEveryFrameAndNeverStepsBackWhilePlaying() {
        val position = PlaybackPosition()
        val head = playing(position)
        position.report(10_000L, sampledAtNanos = 1_000 * ms)
        var last = -1L
        var distinct = 0
        for (frame in 0..120) {
            head.tick((1_000 + frame * 16L) * ms)
            // A fresh reading arrives twice a second, a little behind where the ring has run to.
            if (frame % 31 == 30) position.report(10_000L + frame * 16L - 12L, sampledAtNanos = (1_000 + frame * 16L) * ms)
            val now = head.positionMs()
            assertTrue(now >= last, "frame $frame: $now is not before $last")
            if (now != last) distinct++
            last = now
        }
        assertTrue(distinct > 100, "moved on nearly every one of 121 frames, not once per reading: $distinct")
    }

    @Test fun itIsFrozenWhenPaused() {
        val position = PlaybackPosition()
        val head = LivePlayhead(position)
        position.report(42_000L, sampledAtNanos = 1_000 * ms)
        head.tick(5_000 * ms)
        assertEquals(42_000L, head.positionMs())
        head.tick(9_000 * ms)
        assertEquals(42_000L, head.positionMs())
    }

    @Test fun itIsFrozenWhileTheEngineIsStillWaitingOnAudio() {
        val position = PlaybackPosition()
        val head = playing(position)
        position.advancing = false
        position.report(7_000L, sampledAtNanos = 1_000 * ms)
        head.tick(1_500 * ms)
        assertEquals(7_000L, head.positionMs())
    }

    @Test fun aSeekLandsAtOnceBackwardsAndForwards() {
        val position = PlaybackPosition()
        val head = playing(position)
        position.report(100_000L, sampledAtNanos = 1_000 * ms)
        head.tick(1_300 * ms)
        assertEquals(100_300L, head.positionMs())
        position.seeks++
        position.report(20_000L, sampledAtNanos = 1_310 * ms)
        head.tick(1_320 * ms)
        assertEquals(20_010L, head.positionMs(), "back at once, no sweep")
        position.seeks++
        position.report(150_000L, sampledAtNanos = 1_330 * ms)
        head.tick(1_330 * ms)
        assertEquals(150_000L, head.positionMs())
    }

    @Test fun aSeekThatIsNotAnnouncedButIsFarStillSnaps() {
        val position = PlaybackPosition()
        val head = playing(position)
        position.report(100_000L, sampledAtNanos = 1_000 * ms)
        head.tick(1_100 * ms)
        head.positionMs()
        position.report(30_000L, sampledAtNanos = 1_110 * ms)
        head.tick(1_110 * ms)
        assertEquals(30_000L, head.positionMs())
    }

    @Test fun aReportThatStopsDoesNotLetTheRingRunAwayForever() {
        assertEquals(10_000L + PlayheadMath.MAX_CARRY_MS, PlayheadMath.at(10_000L, 1 * ms, 60_000 * ms, advancing = true))
    }

    @Test fun theFractionIsClampedAndZeroWithoutALength() {
        val position = PlaybackPosition()
        val head = playing(position)
        position.report(90_000L, sampledAtNanos = 1_000 * ms)
        head.tick(1_000 * ms)
        assertEquals(0f, head.fraction(0L))
        assertEquals(1f, head.fraction(60_000L))
        assertEquals(0.5f, head.fraction(180_000L))
    }
}
