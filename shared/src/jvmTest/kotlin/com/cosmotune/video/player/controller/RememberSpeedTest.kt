package com.cosmotune.video.player.controller

import com.cosmotune.video.settings.VideoSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RememberSpeedTest {
    @Test fun aVideoStartsAtItsOwnSpeedOnlyWhenAsked() {
        val s = VideoSettings(defaultSpeed = 1.25f, speedByVideo = mapOf("v" to 2f))
        assertEquals(1.25f, startSpeed(s, "v", short = false), "off: the default")
        assertEquals(2f, startSpeed(s.copy(rememberSpeed = true), "v", short = false))
        assertEquals(1.25f, startSpeed(s.copy(rememberSpeed = true), "other", short = false))
        assertEquals(1.25f, startSpeed(s.copy(rememberSpeed = true), "v", short = true), "Shorts keep the default")
    }

    @Test fun theListKeepsTheNewestAndDropsTheNormalSpeed() {
        val many = (1..300).associate { "v$it" to 1.5f }
        val next = remembered(many, "new", 2f)
        assertEquals(300, next.size)
        assertEquals(2f, next["new"])
        assertEquals(null, next["v1"])
        assertEquals(null, remembered(mapOf("a" to 2f), "a", 1f)["a"])
    }
}
