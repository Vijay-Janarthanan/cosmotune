package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioAutomationTest {
    private val jazz = RadioStation("1", "Jazz FM", "https://r.example/jazz")
    private val news = RadioStation("2", "BBC World Service", "https://r.example/news")
    private val saved = PodioSaved(stations = listOf(jazz), recentStations = listOf(news))

    @Test fun aStationIsFoundByItsNameOrTheStartOfIt() {
        assertEquals(jazz, PodioAutomation.station(saved, "jazz fm", null))
        assertEquals(news, PodioAutomation.station(saved, "BBC", null))
        assertEquals(news, PodioAutomation.station(saved, "world", null))
        assertNull(PodioAutomation.station(saved, "Classic", null))
    }

    @Test fun anAddressPlaysEvenWhenNotSaved() {
        assertEquals(jazz, PodioAutomation.station(saved, null, "https://r.example/jazz/"))
        val other = PodioAutomation.station(saved, "Mine", "https://stream.example/live.mp3")!!
        assertEquals("Mine", other.name); assertEquals("https://stream.example/live.mp3", other.url)
        assertNull(PodioAutomation.station(saved, null, "file:///etc/passwd"))
    }
}
