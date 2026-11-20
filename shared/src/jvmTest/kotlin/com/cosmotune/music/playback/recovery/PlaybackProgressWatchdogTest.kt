package com.cosmotune.music.playback.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackProgressWatchdogTest {
    /** A player that reports what a host would read off it, sampled once a second on a fake clock. */
    private class FakePlayer {
        var now = 0L
        var songId: String? = "a"
        var playing = true
        var buffering = false
        var positionMs = 30_000L
        var moving = true

        val watchdog = PlaybackProgressWatchdog(now = { now })
        val verdicts = mutableListOf<PlaybackProgressWatchdog.Verdict>()

        fun run(seconds: Int) = repeat(seconds) {
            now += PlaybackProgressWatchdog.SAMPLE_EVERY_MS
            if (moving && playing && !buffering) positionMs += PlaybackProgressWatchdog.SAMPLE_EVERY_MS
            verdicts += watchdog.sample(songId, playing, buffering, positionMs)
        }

        val recoveries get() = verdicts.filterIsInstance<PlaybackProgressWatchdog.Verdict.Recover>()
    }

    @Test
    fun aSongThatMovesIsLeftAlone() {
        val player = FakePlayer()
        player.run(120)
        assertTrue(player.verdicts.all { it == PlaybackProgressWatchdog.Verdict.Fine })
    }

    @Test
    fun twelveSecondsStandingStillWhilePlayingIsRecovered() {
        val player = FakePlayer()
        player.run(5)
        player.moving = false
        player.run(11)
        assertTrue(player.recoveries.isEmpty(), "eleven seconds is not yet a stall")
        player.run(1)
        val recover = assertIs<PlaybackProgressWatchdog.Verdict.Recover>(player.verdicts.last())
        assertEquals(1, recover.attempt)
        assertEquals(12_000L, recover.stalledMs)
    }

    @Test
    fun bufferingOrPausedIsNotAStall() {
        val player = FakePlayer()
        player.moving = false
        player.buffering = true
        player.run(60)
        player.buffering = false
        player.playing = false
        player.run(60)
        assertTrue(player.recoveries.isEmpty())
        // Back to "playing" with the clock starting over, not from when it stopped.
        player.playing = true
        player.run(11)
        assertTrue(player.recoveries.isEmpty())
        player.run(1)
        assertEquals(1, player.recoveries.size)
    }

    @Test
    fun atMostTwiceASongThenReportedOnce() {
        val player = FakePlayer()
        player.moving = false
        player.run(12 * 5)
        assertEquals(listOf(1, 2), player.recoveries.map { it.attempt })
        assertEquals(1, player.verdicts.count { it == PlaybackProgressWatchdog.Verdict.GaveUp })
    }

    @Test
    fun aNewSongGetsItsOwnTwoRecoveries() {
        val player = FakePlayer()
        player.moving = false
        player.run(12 * 3)
        assertEquals(2, player.recoveries.size)
        player.songId = "b"
        player.positionMs = 0L
        // The first sample of a new song only anchors it.
        player.run(13)
        assertEquals(3, player.recoveries.size)
        assertEquals(1, player.recoveries.last().attempt)
    }

    @Test
    fun aRecoveryThatWorksResetsTheClock() {
        val player = FakePlayer()
        player.moving = false
        player.run(13)
        assertEquals(1, player.recoveries.size)
        // The re-prepare buffers, then plays on.
        player.buffering = true
        player.run(2)
        player.buffering = false
        player.moving = true
        player.run(60)
        assertEquals(1, player.recoveries.size)
        assertFalse(player.verdicts.any { it == PlaybackProgressWatchdog.Verdict.GaveUp })
    }
}

class QueueRecoveryRulesTest {
    @Test
    fun theLastSongFailingStopsInsteadOfGoingRound() {
        assertFalse(QueueRecoveryRules.mayMoveTo(nextId = null, failedSinceAudible = setOf("z")))
    }

    @Test
    fun skipsStopOnceTheyHaveGoneAllTheWayRound() {
        assertTrue(QueueRecoveryRules.mayMoveTo("b", setOf("a")))
        // Repeat-all wrapping back to a song that already failed since anything played.
        assertFalse(QueueRecoveryRules.mayMoveTo("a", setOf("a", "b", "c")))
        // A queue of one under repeat-all: the next song is the one that just failed.
        assertFalse(QueueRecoveryRules.mayMoveTo("a", setOf("a")))
    }

    @Test
    fun repeatAllWithShuffleDealsAFreshRoundOnlyOnTheWrap() {
        assertTrue(QueueRecoveryRules.reshuffleOnWrap(shuffle = true, repeatAll = true, fromIndex = 9, toIndex = 0, queueSize = 10))
        assertFalse(QueueRecoveryRules.reshuffleOnWrap(shuffle = false, repeatAll = true, fromIndex = 9, toIndex = 0, queueSize = 10))
        assertFalse(QueueRecoveryRules.reshuffleOnWrap(shuffle = true, repeatAll = false, fromIndex = 9, toIndex = 0, queueSize = 10))
        assertFalse(QueueRecoveryRules.reshuffleOnWrap(shuffle = true, repeatAll = true, fromIndex = 3, toIndex = 4, queueSize = 10))
        assertFalse(QueueRecoveryRules.reshuffleOnWrap(shuffle = true, repeatAll = true, fromIndex = 5, toIndex = 0, queueSize = 10), "a jump back, not a wrap")
        assertFalse(QueueRecoveryRules.reshuffleOnWrap(shuffle = true, repeatAll = true, fromIndex = 1, toIndex = 0, queueSize = 2), "two songs have one order")
    }
}
