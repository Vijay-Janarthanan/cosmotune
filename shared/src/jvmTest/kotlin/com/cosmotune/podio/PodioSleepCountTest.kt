package com.cosmotune.podio

import kotlin.test.*

class PodioSleepCountTest {
    @Test fun theLastEpisodeHandsOverToEndOfEpisode() {
        var armed = 0
        PodioSleepCount.start(3) { armed++ }
        assertEquals(3, PodioSleepCount.left.value)
        PodioSleepCount.episodeChanged()
        assertEquals(2, PodioSleepCount.left.value)
        assertEquals(0, armed)
        PodioSleepCount.episodeChanged()
        assertEquals(1, armed, "the last one ends the listening")
        assertEquals(0, PodioSleepCount.left.value)
        PodioSleepCount.episodeChanged()
        assertEquals(1, armed)
        PodioSleepCount.start(1) { armed++ }
        assertEquals(2, armed)
        PodioSleepCount.start(2) { armed++ }
        PodioSleepCount.cancel()
        PodioSleepCount.episodeChanged()
        assertEquals(2, armed)
    }
}
