package com.cosmotune.ui.fullscreen

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeSeekTest {
    @Test fun aSwipeAcrossTheWholePictureMovesTwoMinutes() {
        assertEquals(720_000L, swipeSeekTarget(600_000, 1f, 5_400_000))
        assertEquals(540_000L, swipeSeekTarget(600_000, -0.5f, 5_400_000))
    }

    @Test fun aShortVideoMovesByItsOwnLengthAndNeverPastItsEnds() {
        assertEquals(45_000L, swipeSeekTarget(30_000, 0.25f, 60_000))
        assertEquals(60_000L, swipeSeekTarget(50_000, 1f, 60_000))
        assertEquals(0L, swipeSeekTarget(10_000, -1f, 5_400_000))
    }

    @Test fun theShiftIsWrittenWithItsSign() {
        assertEquals("+0:45", swipeShift(45_000))
        assertEquals("−1:10", swipeShift(-70_000))
    }
}
