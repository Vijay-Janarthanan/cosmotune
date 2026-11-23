package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioLiveTitleTest {
    @Test fun aTitleHeardOnTheStationIsRecentForThatStationOnly() {
        PodioLiveTitle.publish("https://radio.example/live/", "Artist - Song", atMs = 1_000)
        assertEquals("Artist - Song", PodioLiveTitle.recent("https://RADIO.example/live", nowMs = 2_000))
        assertNull(PodioLiveTitle.recent("https://other.example/live", nowMs = 2_000))
    }

    @Test fun anOldOrBlankTitleIsNotRecent() {
        PodioLiveTitle.publish("https://radio.example/a", "Old", atMs = 0)
        assertNull(PodioLiveTitle.recent("https://radio.example/a", nowMs = 200_000))
        PodioLiveTitle.publish("https://radio.example/a", "   ", atMs = 199_000)
        assertNull(PodioLiveTitle.recent("https://radio.example/a", nowMs = 200_000))
    }
}
