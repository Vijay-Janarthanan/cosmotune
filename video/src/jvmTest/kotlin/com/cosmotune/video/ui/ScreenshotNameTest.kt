package com.cosmotune.video.ui

import com.cosmotune.video.ui.player.screenshotName
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenshotNameTest {
    @Test fun theNameIsTheTitleAndTheMoment() {
        assertEquals("Desk tour 02-05.png", screenshotName("Desk tour", 125_400))
        assertEquals("Live 1-01-01.png", screenshotName("Live", 3_661_000))
    }

    @Test fun charactersFileSystemsRefuseAreLeftOut() {
        assertEquals("a b c d 00-00.png", screenshotName("a/b:c?\"d\"", 0))
        assertEquals("Cosmotune 00-00.png", screenshotName("  ", 0))
    }
}
