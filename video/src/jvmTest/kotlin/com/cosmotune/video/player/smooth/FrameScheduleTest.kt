package com.cosmotune.video.player.smooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's frame timing: when a frame is due, which pair is on screen, and how long the sound is held back. */
class FrameScheduleTest {
    private val ms = 1_000_000L

    @Test
    fun aReleaseTimeNearTheArrivalIsTheFramesTime() {
        val arrival = 10_000 * ms
        assertEquals(arrival + 30 * ms, SmoothSchedule.frameTime(arrival + 30 * ms, arrival, Long.MIN_VALUE))
        assertEquals(arrival - 20 * ms, SmoothSchedule.frameTime(arrival - 20 * ms, arrival, Long.MIN_VALUE))
    }

    @Test
    fun aPresentationTimeOnTheMediasOwnClockIsNotARelease() {
        val arrival = 50_000_000 * ms
        // 12 s into the video, or nothing at all: the frame is taken to be due when it arrived
        assertEquals(arrival, SmoothSchedule.frameTime(12_000 * ms, arrival, Long.MIN_VALUE))
        assertEquals(arrival, SmoothSchedule.frameTime(0L, arrival, Long.MIN_VALUE))
    }

    @Test
    fun framesStayInOrder() {
        val last = 1_000 * ms
        assertEquals(last + 1, SmoothSchedule.frameTime(last - 5 * ms, last, last))
        assertTrue(SmoothSchedule.frameTime(last - 5 * ms, last - 5 * ms, last) > last)
    }

    @Test
    fun theDrawnPictureIsEvaluatedForTheRefreshAfterTheCallback() {
        assertEquals(1_016_666_666L, SmoothSchedule.displayTime(1_000_000_000L, DisplayRates.periodNanos(60f)))
    }

    @Test
    fun theScreenRestsWhenNothingMovesAndNothingWaits() {
        val interval = 41_666_666L
        assertTrue(SmoothSchedule.animating(1_020 * ms, 1_000 * ms, interval, pending = false))
        assertTrue(!SmoothSchedule.animating(1_200 * ms, 1_000 * ms, interval, pending = false))
        assertTrue(SmoothSchedule.animating(1_200 * ms, 1_000 * ms, interval, pending = true))
        assertTrue(!SmoothSchedule.animating(1_020 * ms, 1_000 * ms, null, pending = false))
    }

    @Test
    fun gpuWorkIsTheObservedTimeLessHalfATick() {
        assertEquals(8.0, SmoothSchedule.gpuWorkMs(16.0, 16.0))
        assertEquals(0.0, SmoothSchedule.gpuWorkMs(3.0, 16.0))
    }

    @Test
    fun theSoundIsHeldBackOneIntervalOfTheVideosOwnTime() {
        // 24 fps at normal speed: 41.7 ms; at 2x the same interval of the clock holds twice the video
        assertEquals(1.0 / 24, SmoothSchedule.audioDelaySeconds((1e9 / 24).toLong(), 1f), 1e-6)
        assertEquals(2.0 / 24, SmoothSchedule.audioDelaySeconds((1e9 / 24).toLong(), 2f), 1e-6)
        // never more than 100 ms of the clock
        assertEquals(0.1, SmoothSchedule.audioDelaySeconds(500 * ms, 1f), 1e-9)
    }

    @Test
    fun theAnalysisPictureIsSizedAsTheCpuExtractorSizesIt() {
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 640 to 360, 1080 to 1920, 3840 to 2160, 100 to 50, 720 to 480)) {
            val size = SmoothSchedule.analysisSize(w, h)
            val extractor = LumaExtractor(w, h)
            assertEquals(extractor.width, size[0], "$w x $h width")
            assertEquals(extractor.height, size[1], "$w x $h height")
        }
    }

    @Test
    fun bigFramesAreKeptAtMost1920OnTheirLongerSide() {
        assertEquals(listOf(1920, 1080), SmoothSchedule.frameSize(3840, 2160).toList())
        assertEquals(listOf(1080, 1920), SmoothSchedule.frameSize(2160, 3840).toList())
        assertEquals(listOf(1280, 720), SmoothSchedule.frameSize(1280, 720).toList())
    }

    // ---- the planner ----

    private fun planner() = FramePlanner(4)

    private fun FramePlanner.add(time: Long): Int {
        val slot = acquire()
        assertTrue(slot >= 0, "a free slot")
        offer(slot, time)
        return slot
    }

    @Test
    fun nothingIsShownBeforeTheFirstFrame() {
        assertNull(planner().select(1_000))
    }

    @Test
    fun theFirstFrameIsShownAloneThenPairsFormAsFramesAreDue() {
        val p = planner()
        val a = p.add(100)
        val first = assertNotNull(p.select(100))
        assertEquals(-1, first.older)
        assertEquals(a, first.newer)
        val b = p.add(140)
        // b is early: a is still the picture until b is due
        assertEquals(a, assertNotNull(p.select(120)).newer)
        val pair = assertNotNull(p.select(140))
        assertEquals(a, pair.older)
        assertEquals(b, pair.newer)
        assertEquals(40, pair.gap)
        assertEquals(140, pair.newerAt)
    }

    @Test
    fun aPairMovesPastAFrameOnlyWhenTheNextOneIsDue() {
        val p = planner()
        val a = p.add(100)
        val b = p.add(140)
        val c = p.add(180)
        assertEquals(a, assertNotNull(p.select(99)).newer)
        assertEquals(b, assertNotNull(p.select(150)).newer)
        assertEquals(a, assertNotNull(p.select(150)).older)
        val third = assertNotNull(p.select(1_000))
        assertEquals(c, third.newer)
        assertEquals(b, third.older)
        // a has been passed and is free again
        val reused = p.acquire()
        assertEquals(a, reused)
    }

    @Test
    fun theNumberOfThePairChangesWithEachNewPair() {
        val p = planner()
        p.add(100)
        p.add(140)
        val x = assertNotNull(p.select(100)).no
        val y = assertNotNull(p.select(140)).no
        assertTrue(y > x)
        assertEquals(y, assertNotNull(p.select(141)).no)
    }

    @Test
    fun showingTheNewestSkipsTheWait() {
        val p = planner()
        p.add(100)
        p.add(10_000)
        val shown = assertNotNull(p.showNewest())
        assertEquals(p.last, shown.newer)
        assertEquals(0, p.pending)
    }

    @Test
    fun aPileOfWaitingFramesDropsTheOldestButKeepsTheNewest() {
        val p = planner()
        p.add(100)
        val w1 = p.add(1_000)
        val w2 = p.add(1_040)
        val w3 = p.add(1_080)
        assertEquals(-1, p.acquire(), "the ring is full")
        assertEquals(w1, p.dropOldestWaiting())
        assertEquals(w1, p.acquire())
        // two waiting frames are left; the last one is never dropped
        p.offer(w1, 1_120)
        assertEquals(w2, p.dropOldestWaiting())
        val pair = assertNotNull(p.select(2_000))
        assertEquals(w3, pair.older)
        assertEquals(w1, pair.newer)
    }

    @Test
    fun clearFreesEverything() {
        val p = planner()
        p.add(1)
        p.add(2)
        p.clear()
        assertNull(p.select(100))
        assertEquals(0, p.acquire())
        assertEquals(-1, p.last)
    }

    @Test
    fun aSlotGivenBackCanBeTakenAgain() {
        val p = planner()
        val s = p.acquire()
        p.giveBack(s)
        assertEquals(s, p.acquire())
    }
}
