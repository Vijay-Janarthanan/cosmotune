package com.cosmotune.video.ui

import com.cosmotune.video.ui.player.TapZone
import com.cosmotune.video.ui.player.swipeZone
import com.cosmotune.video.ui.player.swipedSpeed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Full screen's drags: how wide the side areas are, and the speed a drag in the middle sets. */
class SwipeGesturesTest {
    @Test fun theSideAreasFollowTheSetting() {
        assertEquals(TapZone.LEFT, swipeZone(0.3f, 0.4f))
        assertEquals(TapZone.MIDDLE, swipeZone(0.3f, 0.25f))
        assertEquals(TapZone.RIGHT, swipeZone(0.8f, 0.25f))
        assertEquals(TapZone.MIDDLE, swipeZone(0.5f, 0.5f))
    }

    @Test fun upIsFasterInQuarterSteps() {
        assertEquals(1f, swipedSpeed(1f, 10f, 1000f), "a small move keeps the speed")
        assertEquals(1.75f, swipedSpeed(1f, -250f, 1000f))
        assertEquals(0.25f, swipedSpeed(1f, 1000f, 1000f), "never below 0.25x")
        assertEquals(4f, swipedSpeed(2f, -2000f, 1000f), "never above 4x")
    }
}
