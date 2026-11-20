package com.cosmotune.music.playback.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkOutageTest {
    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun scheduleBacksOffThenHoldsAtTheCeiling() {
        val schedule = ReconnectSchedule.DEFAULT
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L), (0..6).map(schedule::delayFor))
        assertEquals(1_000L, schedule.delayFor(-1))
        assertEquals(120_000L, schedule.budgetMs)
    }

    @Test
    fun retriesOnTheScheduleUntilTwoMinutesThenGivesUp() {
        val clock = FakeClock()
        val outage = NetworkOutage(clock::now)
        val waits = mutableListOf<Long>()
        while (true) {
            when (val decision = outage.onNetworkFailure()) {
                is NetworkOutage.Decision.Retry -> {
                    assertTrue(outage.reconnecting)
                    assertEquals(waits.size + 1, decision.attempt)
                    waits += decision.delayMs
                    clock.advance(decision.delayMs)
                }
                NetworkOutage.Decision.GiveUp -> break
            }
        }
        // 1 + 2 + 4 + 8 = 15 s, then 10 s steps to 115 s, then the last wait is cut to land on 120 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L) + List(10) { 10_000L } + 5_000L, waits)
        assertEquals(120_000L, waits.sum())
        assertTrue(outage.gaveUp)
        assertFalse(outage.reconnecting)
        // Still the same outage: a second song failing gets no wait of its own.
        assertIs<NetworkOutage.Decision.GiveUp>(outage.onNetworkFailure())
    }

    @Test
    fun theFailuresElapsedTimeNotTheAttemptCountEndsTheWait() {
        val clock = FakeClock()
        val outage = NetworkOutage(clock::now)
        assertIs<NetworkOutage.Decision.Retry>(outage.onNetworkFailure())
        // Each attempt itself took a long time to fail (a resolve walk timing out).
        clock.advance(70_000L)
        assertIs<NetworkOutage.Decision.Retry>(outage.onNetworkFailure())
        clock.advance(50_000L)
        assertIs<NetworkOutage.Decision.GiveUp>(outage.onNetworkFailure())
    }

    @Test
    fun networkBackRetriesAtOnceOnlyWhileAReconnectIsWaiting() {
        val clock = FakeClock()
        val outage = NetworkOutage(clock::now)
        assertFalse(outage.onNetworkAvailable(), "nothing is waiting")
        outage.onNetworkFailure()
        assertTrue(outage.onNetworkAvailable())
        clock.advance(121_000L)
        outage.onNetworkFailure()
        assertFalse(outage.onNetworkAvailable(), "the outage has given up; the listener's play starts the next one")
    }

    @Test
    fun audioResumingEndsTheOutageAndReportsHowLongItLasted() {
        val clock = FakeClock()
        val outage = NetworkOutage(clock::now)
        assertNull(outage.onPlaybackResumed())
        outage.onNetworkFailure()
        clock.advance(7_000L)
        outage.onNetworkFailure()
        clock.advance(2_000L)
        assertEquals(9_000L, outage.onPlaybackResumed())
        assertFalse(outage.reconnecting)
        // A later drop is a new outage with the full schedule again.
        val next = assertIs<NetworkOutage.Decision.Retry>(outage.onNetworkFailure())
        assertEquals(1, next.attempt)
        assertEquals(1_000L, next.delayMs)
    }

    @Test
    fun oneSkipPerOutage() {
        val clock = FakeClock()
        val outage = NetworkOutage(clock::now)
        outage.onNetworkFailure()
        assertTrue(outage.claimSkip())
        assertFalse(outage.claimSkip())
        assertFalse(outage.claimSkip())
        outage.onPlaybackResumed()
        outage.onNetworkFailure()
        assertTrue(outage.claimSkip(), "a new outage may cost one song again")
        outage.reset()
        assertTrue(outage.claimSkip(), "and so may the listener's own fresh start")
    }

    /**
     * The whole rule against a fake queue, the way both players drive it: the first song never
     * starts and is retried for two minutes, then skipped once; the second fails in the same
     * outage and the queue stops on it instead of walking on.
     */
    @Test
    fun aDeadConnectionCostsAtMostOneSongThenStops() {
        val clock = FakeClock()
        val player = FakeQueuePlayer(listOf("a", "b", "c", "d", "e"), NetworkOutage(clock::now))
        var guard = 0
        while (!player.stopped && guard++ < 1_000) {
            val wait = player.failWithNetworkError() ?: continue
            clock.advance(wait)
        }
        assertTrue(player.stopped)
        assertEquals(listOf("a"), player.skipped)
        assertEquals("b", player.current)
        assertTrue(player.retries >= 14, "song a was retried on the backoff: ${player.retries}")
    }

    @Test
    fun aConnectionThatReturnsKeepsTheSongAndItsPosition() {
        val clock = FakeClock()
        val player = FakeQueuePlayer(listOf("a", "b"), NetworkOutage(clock::now), positionMs = 63_000L)
        repeat(4) { clock.advance(player.failWithNetworkError() ?: 0L) }
        player.networkBack()
        assertTrue(player.playing)
        assertEquals("a", player.current)
        assertEquals(63_000L, player.positionMs)
        assertEquals(emptyList(), player.skipped)
    }

    /** The decision steps both players take on a network failure, with no media behind them. */
    private class FakeQueuePlayer(
        private val queue: List<String>,
        private val outage: NetworkOutage,
        var positionMs: Long = 0L,
    ) {
        private var index = 0
        val current: String get() = queue[index]
        val skipped = mutableListOf<String>()
        var retries = 0
        var stopped = false
        var playing = false

        /** The stream failed for want of a network. Returns the wait before the next attempt, or null. */
        fun failWithNetworkError(): Long? {
            playing = false
            return when (val decision = outage.onNetworkFailure()) {
                is NetworkOutage.Decision.Retry -> {
                    retries++
                    decision.delayMs
                }
                NetworkOutage.Decision.GiveUp -> {
                    if (index < queue.lastIndex && outage.claimSkip()) {
                        skipped += current
                        index++
                        positionMs = 0L
                    } else {
                        stopped = true
                    }
                    null
                }
            }
        }

        fun networkBack() {
            if (outage.onNetworkAvailable()) {
                playing = true
                outage.onPlaybackResumed()
            }
        }
    }
}
