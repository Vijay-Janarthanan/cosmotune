package com.cosmotune.canvas

import com.opencanvas.core.sync.CanvasStreamChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stream worth playing for the box a music video is drawn in, never above the quality setting. */
class CanvasStreamChoiceTest {
    @Test fun theDrawnHeightIsSetByTheSideThatOverflows() {
        assertEquals(454, CanvasStreamChoice.drawnHeight(804, 454, 1920, 1080), "a 16:9 box: the picture's own shape")
        assertEquals(1000, CanvasStreamChoice.drawnHeight(800, 1000, 1280, 720), "a tall box crops the sides, the height sets it")
        assertEquals(0, CanvasStreamChoice.drawnHeight(0, 0, 1280, 720))
    }

    @Test fun aFastStartCopyUnderALargeBoxAsksForTheTallerStream() {
        assertEquals(720, CanvasStreamChoice.upgradeHeight(1280, 720, 640, 360, capHeight = 1080))
        assertEquals(1080, CanvasStreamChoice.upgradeHeight(1920, 1080, 640, 360, capHeight = 1080))
        assertEquals(1080, CanvasStreamChoice.upgradeHeight(2560, 1440, 1280, 720, capHeight = 1080), "a box taller than Full HD: the tallest there is")
    }

    @Test fun theQualitySettingCapsIt() {
        assertEquals(480, CanvasStreamChoice.upgradeHeight(1920, 1080, 640, 360, capHeight = 480))
        assertNull(CanvasStreamChoice.upgradeHeight(1920, 1080, 640, 360, capHeight = 360))
    }

    @Test fun aStreamAsTallAsTheBoxNeedsStays() {
        assertNull(CanvasStreamChoice.upgradeHeight(804, 454, 1280, 720, capHeight = 1080), "720p for a 454 px tall box")
        assertNull(CanvasStreamChoice.upgradeHeight(1920, 1080, 1440, 1072, capHeight = 1080), "1072p is Full HD")
        assertNull(CanvasStreamChoice.upgradeHeight(1280, 676, 1280, 676, capHeight = 1080), "a widescreen 676p copy fills a 676 px box")
        assertNull(CanvasStreamChoice.upgradeHeight(640, 360, 640, 360, capHeight = 1080), "a small box needs no more than 360p")
    }
}
