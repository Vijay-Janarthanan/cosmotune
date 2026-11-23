package com.cosmotune.podio

import kotlin.test.*

class PodioHistoryTest {
    private val one = RadioStation("1", "One FM", "https://one.example/live")
    private val two = RadioStation("2", "Two FM", "https://two.example/live")

    @Test fun recentStationsKeepOneEntryEachLatestFirst() {
        var saved = PodioHistory.played(PodioSaved(), one)
        saved = PodioHistory.played(saved, two)
        saved = PodioHistory.played(saved, one)
        assertEquals(listOf("1", "2"), saved.recentStations.map { it.id })
        assertEquals(PodioHistory.STATIONS, (1..50).fold(PodioSaved()) { acc, n -> PodioHistory.played(acc, RadioStation("$n", "S$n", "https://s.example/$n")) }.recentStations.size)
    }

    @Test fun aSongIsNotedOnceAsItPlaysAndTheStationsOwnNameIsNotASong() {
        var saved = PodioHistory.heard(PodioSaved(), "Artist - Song", one, 1)
        saved = PodioHistory.heard(saved, " Artist - Song ", one, 2)
        saved = PodioHistory.heard(saved, "One FM", one, 3)
        saved = PodioHistory.heard(saved, "Artist - Song", two, 4)
        assertEquals(listOf("Two FM", "One FM"), saved.heardSongs.map { it.station })
        assertEquals("Artist - Song · Two FM\nArtist - Song · One FM", PodioHistory.songsText(saved.heardSongs))
        assertTrue(PodioHistory.clear(saved).heardSongs.isEmpty())
    }
}
