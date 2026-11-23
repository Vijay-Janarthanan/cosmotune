package com.cosmotune.podio

import kotlin.test.*

class PodioSmartListsTest {
    private val a = PodcastShow("a", "A", "", "https://publisher.example/a")
    private val b = PodcastShow("b", "B", "", "https://publisher.example/b")
    private val now = PodioRefresh.publishedAt("2026-11-23")!!
    private fun ep(id: String, show: PodcastShow, minutes: Long, date: String) = PodcastEpisode(id, id, show.title, "https://publisher.example/$id.mp3", durationSeconds = minutes * 60, published = date) to show
    private val all = listOf(ep("short-new", a, 20, "2026-11-22"), ep("long-new", a, 90, "2026-11-21"), ep("short-old", b, 10, "2026-09-01"), ep("b-new", b, 25, "2026-11-23"))
    private fun ids(list: PodioSmartList, saved: PodioSaved = PodioSaved(), downloaded: Set<String> = emptySet()) =
        PodioSmartLists.match(list, all, saved, downloaded, now).map { it.first.id }

    @Test fun rulesAreKeptTogetherNewestFirst() {
        assertEquals(listOf("b-new", "short-new"), ids(PodioSmartList("x", "Quick", maxMinutes = 30, withinDays = 7)))
        assertEquals(listOf("short-new", "long-new"), ids(PodioSmartList("x", "A only", shows = setOf("a"))))
        assertEquals(4, ids(PodioSmartList("x", "All", unplayed = false)).size)
    }

    @Test fun playedStartedAndDownloadedAreTheListenersOwn() {
        val saved = PodioSaved(completedEpisodes = setOf("podio:episode:b-new"), progress = mapOf("podio:episode:b-new" to 1L, "podio:episode:long-new" to 60_000L))
        assertFalse("b-new" in ids(PodioSmartList("x", "Unplayed"), saved))
        assertEquals(listOf("long-new"), ids(PodioSmartList("x", "Started", started = true), saved))
        assertEquals(listOf("short-old"), ids(PodioSmartList("x", "Offline", downloaded = true), downloaded = setOf("podio:episode:short-old")))
    }

    @Test fun listsArePutInPlaceNamedAndDescribed() {
        var saved = PodioSmartLists.put(PodioSaved(), PodioSmartList("1", "  ", maxMinutes = 30, withinDays = 7))
        assertEquals("My list", saved.smartLists.single().name)
        saved = PodioSmartLists.put(saved, saved.smartLists.single().copy(name = "Quick"))
        assertEquals(listOf("Quick"), saved.smartLists.map { it.name })
        assertEquals("Unplayed · 30 min or less · This week", PodioSmartLists.describe(saved.smartLists.single()))
        assertTrue(PodioSmartLists.remove(saved, "1").smartLists.isEmpty())
    }
}
