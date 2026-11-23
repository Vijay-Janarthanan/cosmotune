package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPodioTest {
    private val show = PodcastShow("s1", "Show One", "Host", "https://feeds.example/one.xml", people = listOf(PodcastPerson("Ann")))
    private val station = RadioStation("r1", "Radio One", "https://radio.example/live", offline = true)
    private val saved = PodioSaved(shows = listOf(show), stations = listOf(station),
        progress = mapOf("podio:episode:a" to 61_000L, "podio:episode:b" to 3_600_000L), completedEpisodes = setOf("podio:episode:b"))

    @Test fun followsStationsAndPositionsTravelButSignInsDoNot() {
        val withSecret = saved.copy(shows = saved.shows + show.copy(id = "s2", feed = "https://me:pw@private.example/feed"))
        val snap = SyncPodio.snapshot(withSecret)
        assertEquals(setOf("show:s1", "station:r1", "progress:podio:episode:a", "progress:podio:episode:b"), snap.keys)
        assertFalse(snap.getValue("show:s1").containsKey("people"), "a show's hosts come from its own feed on each device")
    }

    @Test fun anotherDevicesChangesAreLaidOver() {
        val other = SyncPodio.snapshot(PodioSaved(
            shows = listOf(show.copy(id = "s3", title = "Three", feed = "https://feeds.example/three.xml")),
            stations = listOf(station.copy(name = "Radio One HD", offline = false)),
            progress = mapOf("podio:episode:a" to 1_200_000L), completedEpisodes = setOf("podio:episode:a")))
        val next = SyncPodio.apply(saved, other, deletes = setOf("show:s1", "progress:podio:episode:b"))
        assertEquals(listOf("s3"), next.shows.map { it.id })
        assertEquals("Radio One HD", next.stations.single().name)
        assertTrue(next.stations.single().offline, "this device's own check of the station stays")
        assertEquals(mapOf("podio:episode:a" to 1_200_000L), next.progress)
        assertEquals(setOf("podio:episode:a"), next.completedEpisodes)
    }

    @Test fun aShowFollowedHereKeepsItsOwnFeedDetails() {
        val next = SyncPodio.apply(saved, mapOf("show:s1" to SyncPodio.snapshot(saved).getValue("show:s1")), emptySet())
        assertEquals(listOf(PodcastPerson("Ann")), next.shows.single().people)
    }

    @Test fun playingDoesNotSignalAChangeUntilAMinutePasses() {
        val later = saved.copy(progress = saved.progress + ("podio:episode:a" to 75_000L))
        assertEquals(SyncPodio.changeSignal(saved), SyncPodio.changeSignal(later))
        val minuteOn = saved.copy(progress = saved.progress + ("podio:episode:a" to 125_000L))
        assertTrue(SyncPodio.changeSignal(saved) != SyncPodio.changeSignal(minuteOn))
    }

    @Test fun brokenOrForeignEntriesAreLeftOut() {
        val next = SyncPodio.apply(saved, mapOf(
            "progress:notpodio" to kotlinx.serialization.json.buildJsonObject { put("positionMs", kotlinx.serialization.json.JsonPrimitive(5)) },
            "show:x" to kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("y")) },
        ), emptySet())
        assertEquals(saved, next)
    }
}
