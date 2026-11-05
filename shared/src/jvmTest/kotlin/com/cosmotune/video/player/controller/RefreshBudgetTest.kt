package com.cosmotune.video.player.controller

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The refresh budget (spec 6.1: at most twice in five minutes), and the two small clocks the controller's ticker uses. */
class RefreshBudgetTest {
    private var now = 1_000_000L

    @Test
    fun allowsTwoUsesInFiveMinutesAndRefusesTheThird() {
        val budget = RefreshBudget { now }
        assertTrue(budget.tryUse())
        now += 60_000
        assertTrue(budget.tryUse())
        now += 60_000
        assertFalse(budget.tryUse())
        now += 60_000
        assertFalse(budget.tryUse(), "a refusal does not use the budget up or reset it")
    }

    @Test
    fun aUseFreesFiveMinutesAfterItWasMade() {
        val budget = RefreshBudget { now }
        assertTrue(budget.tryUse())             // at 0
        now += 100_000
        assertTrue(budget.tryUse())             // at 100 s
        now += 199_999                          // 299.999 s: the first use is still inside the window
        assertFalse(budget.tryUse())
        now += 1                                // 300 s: the first use is out, the second (at 100 s) is not
        assertTrue(budget.tryUse())
        assertFalse(budget.tryUse(), "the second and the third use are inside the window now")
    }

    @Test
    fun theLimitAndTheWindowCanBeChosen() {
        val budget = RefreshBudget(max = 1, windowMs = 1_000) { now }
        assertTrue(budget.tryUse())
        assertFalse(budget.tryUse())
        now += 1_000
        assertTrue(budget.tryUse())
    }

    @Test
    fun theDefaultsAreTwoInFiveMinutes() {
        val budget = RefreshBudget { now }
        assertTrue(budget.max == 2 && budget.windowMs == 5 * 60_000L)
    }
}

/** History's clock (spec 6.1: every 10 s while playing). */
class HistoryTickerTest {
    private var now = 50_000L

    @Test
    fun isDueEveryTenSecondsOfPlayingAndOnlyOnce() {
        val ticker = HistoryTicker { now }
        ticker.restart()
        now += 9_999
        assertFalse(ticker.poll())
        now += 1
        assertTrue(ticker.poll())
        assertFalse(ticker.poll(), "a poll that said yes starts the next ten seconds")
        now += 10_000
        assertTrue(ticker.poll())
    }

    @Test
    fun restartingStartsTheTenSecondsAgain() {
        val ticker = HistoryTicker { now }
        ticker.restart()
        now += 8_000
        ticker.restart()
        now += 8_000
        assertFalse(ticker.poll())
        now += 2_000
        assertTrue(ticker.poll())
    }

    @Test
    fun theIntervalCanBeChosen() {
        val ticker = HistoryTicker(intervalMs = 500) { now }
        ticker.restart()
        now += 499
        assertFalse(ticker.poll())
        now += 1
        assertTrue(ticker.poll())
    }
}

/** Auto's step down (spec 6.3: more than 10 % of the frames dropped over 10 s). */
class FrameDropWatchTest {
    private var now = 0L

    @Test
    fun saysNothingBeforeTheWindowIsOver() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 0)
        now = 9_999
        assertFalse(watch.check(droppedFrames = 5_000, fps = 60, speed = 1f))
    }

    @Test
    fun saysYesWhenMoreThanTenPercentOfTheFramesDropped() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 0)
        now = 10_000                                   // 600 frames at 60 fps: 61 dropped is more than 10 %
        assertTrue(watch.check(droppedFrames = 61, fps = 60, speed = 1f))
    }

    @Test
    fun exactlyTenPercentIsNotMoreThanTenPercent() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 0)
        now = 10_000
        assertFalse(watch.check(droppedFrames = 60, fps = 60, speed = 1f))
    }

    @Test
    fun countsTheFramesThatWereDroppedInTheWindowOnly() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 400)               // the video had dropped 400 before this window
        now = 10_000
        assertFalse(watch.check(droppedFrames = 430, fps = 30, speed = 1f))   // 30 of 300
        now = 20_000
        assertTrue(watch.check(droppedFrames = 480, fps = 30, speed = 1f))    // 50 of 300 since the last window
    }

    @Test
    fun theSpeedRaisesTheNumberOfFramesToShow() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 0)
        now = 10_000                                   // 30 fps at 2x: 600 frames
        assertFalse(watch.check(droppedFrames = 60, fps = 30, speed = 2f))
        now = 20_000
        assertTrue(watch.check(droppedFrames = 61 + 60, fps = 30, speed = 2f))
    }

    @Test
    fun aDropCounterThatWentBackwardsIsNotADrop() {
        val watch = FrameDropWatch { now }
        watch.reset(droppedFrames = 500)
        now = 10_000
        assertFalse(watch.check(droppedFrames = 0, fps = 30, speed = 1f))
    }
}
