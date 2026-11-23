package com.cosmotune.watch

import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.PictureFilter
import com.cosmotune.video.player.VideoPlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchPictureChoiceTest {
    @Test fun theMenuOffersOnlyTheLooksThisPlayerDraws() {
        assertTrue(WatchPictureChoice.of(FakeVideoPlayerEngine()).isEmpty(), "a player that draws no looks offers none")
        val phone = object : VideoPlayerEngine by FakeVideoPlayerEngine() {
            override val pictureFilters = setOf(PictureFilter.NORMAL, PictureFilter.BRIGHTER, PictureFilter.VIVID, PictureFilter.SOFTER)
        }
        assertEquals(listOf("Picture as made", "Brighter", "Vivid", "Softer"), WatchPictureChoice.of(phone).map { it.label })
        assertEquals(PictureFilter.entries.toSet(), WatchPictureChoice.all.map { it.filter }.toSet())
    }
}
