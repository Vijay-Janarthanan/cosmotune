package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals

class PodioCarBrowseTest {
    private val show = PodcastShow("s", "Show", "Host", "https://feeds.example/s.xml")
    private fun entry(id: String) = PodioQueueEntry(PodcastEpisode(id, "Episode $id", "Show", "https://cdn.example/$id.mp3", durationSeconds = 3600), show)

    @Test fun startedEpisodesComeFirstThenUpNextThenUnheardFavourites() {
        val a = entry("a"); val b = entry("b"); val c = entry("c"); val d = entry("d")
        val saved = PodioSaved(progress = mapOf(c.id to 600_000L, d.id to 10_000L), completedEpisodes = setOf(d.id), favouriteEpisodes = listOf(d, c, a))
        val queue = PodioQueueState(entries = listOf(a, b, c))
        assertEquals(listOf(c.id, a.id, b.id), PodioCarBrowse.forYou(saved, queue).map { it.id })
    }

    @Test fun stationsAreSavedOnesThenLatelyPlayedOthers() {
        val one = RadioStation("1", "One", "https://r.example/1"); val two = RadioStation("2", "Two", "https://r.example/2")
        val saved = PodioSaved(stations = listOf(one), recentStations = listOf(two, one))
        assertEquals(listOf("podio:radio:1"), PodioCarBrowse.stations(saved).map { it.id })
        assertEquals(listOf("podio:radio:2"), PodioCarBrowse.recentStations(saved).map { it.id })
    }

    @Test fun aCarStartPicksUpWhereTheEpisodeWasLeft() {
        val saved = PodioSaved(progress = mapOf("podio:episode:x" to 120_000L, "podio:episode:y" to 3_599_000L))
        assertEquals(120_000L, PodioCarBrowse.resumeAt(saved, "podio:episode:x", 3_600_000))
        assertEquals(0L, PodioCarBrowse.resumeAt(saved, "podio:episode:y", 3_600_000))
        assertEquals(0L, PodioCarBrowse.resumeAt(saved, "podio:radio:x", 0))
    }
}
