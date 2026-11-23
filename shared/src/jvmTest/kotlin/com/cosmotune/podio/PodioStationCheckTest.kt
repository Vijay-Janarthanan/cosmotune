package com.cosmotune.podio

import kotlin.test.*

class PodioStationCheckTest {
    @Test fun savedStationsTakeTheDirectorysNewsAndKeepTheirNames() {
        val mine = RadioStation("a", "My name for A", "https://old.example/a")
        val gone = RadioStation("b", "B", "https://b.example")
        val custom = assertNotNull(PodioStationLinks.station("Mine", "https://mine.example/live"))
        val saved = PodioSaved(stations = listOf(mine, gone, custom))
        assertTrue(PodioStationCheck.due(saved, PodioStationCheck.EVERY_MS))
        val fresh = listOf(RadioStation("a", "A", "https://new.example/a", artwork = "https://a.example/logo.png", offline = true))
        val next = PodioStationCheck.merge(saved, fresh, 99)
        assertEquals(RadioStation("a", "My name for A", "https://new.example/a", artwork = "https://a.example/logo.png", offline = true), next.stations[0])
        assertTrue(next.stations[1].removed)
        assertEquals(custom, next.stations[2])
        assertEquals("Not answering lately", PodioStationCheck.status(next.stations[0]))
        assertEquals("Gone from the directory", PodioStationCheck.status(next.stations[1]))
        assertFalse(PodioStationCheck.due(next, 100))
    }
}
