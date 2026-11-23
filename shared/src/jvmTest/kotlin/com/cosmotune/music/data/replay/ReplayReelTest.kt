package com.cosmotune.music.data.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayReelTest {

    private val reel = ReplayReel(cards = 4, holdMs = 3_000, slideMs = 500)

    @Test
    fun lastsEveryHoldAndEverySlideBetweenCards() {
        assertEquals(4 * 3_000L + 3 * 500L, reel.durationMs)
        assertEquals(reel.durationMs * 24 / 1_000, reel.frameCount(24).toLong())
    }

    @Test
    fun aCardHoldsStillThenSlidesToTheNext() {
        val start = reel.at(0)
        assertEquals(0, start.card)
        assertNull(start.next)
        assertEquals(0f, start.slide)
        val sliding = reel.at(3_250)
        assertEquals(0, sliding.card)
        assertEquals(1, sliding.next)
        assertTrue(sliding.slide in 0.3f..0.7f, "half way through the slide is about half moved, was ${sliding.slide}")
        val second = reel.at(3_500)
        assertEquals(1, second.card)
        assertNull(second.next)
    }

    @Test
    fun theLastCardNeverSlidesAway() {
        val end = reel.at(reel.durationMs + 5_000)
        assertEquals(3, end.card)
        assertNull(end.next)
        assertEquals(1f, end.progress[3])
    }

    @Test
    fun theStoryBarsFillCardByCard() {
        val moment = reel.at(3_500 + 1_500)
        assertEquals(listOf(1f, 0.5f, 0f, 0f), moment.progress)
    }

    @Test
    fun theCardSlowlyGrowsWhileItIsShown() {
        assertEquals(1f, reel.at(0).zoom)
        assertTrue(reel.at(2_900).zoom > 1.03f)
        assertTrue(reel.at(2_900).zoom <= 1f + ReplayReel.GROWTH)
    }

    @Test
    fun theSongPlaysFromAboutAThirdIn() {
        assertEquals(60_000L, ReplayReel.clipStartMs(180_000, reel.durationMs))
        assertEquals(45_000L, ReplayReel.clipStartMs(0, reel.durationMs))
        // A short song starts early enough for the clip to fit.
        assertEquals(0L, ReplayReel.clipStartMs(10_000, reel.durationMs))
    }
}
