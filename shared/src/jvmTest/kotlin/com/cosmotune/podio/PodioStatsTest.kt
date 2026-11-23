package com.cosmotune.podio

import java.time.LocalDate
import kotlin.test.*

class PodioStatsTest {
    private val day = LocalDate.parse("2026-11-20")

    @Test fun listeningAddsUpByDayAndShow() {
        var saved = PodioStats.add(PodioSaved(), day, "a", 30 * 60_000L)
        saved = PodioStats.add(saved, day, "b", 10 * 60_000L)
        saved = PodioStats.add(saved, day.plusDays(2), "a", 30 * 60_000L)
        val week = PodioStats.summary(saved, day, day.plusDays(6))
        assertEquals(70 * 60_000L, week.totalMs)
        assertEquals(2, week.days)
        assertEquals(listOf("a", "b"), week.byShow.map { it.first })
        assertEquals(0L, PodioStats.summary(saved, day.plusDays(10), day.plusDays(20)).totalMs)
        assertEquals("1 h 10 min", PodioStats.length(week.totalMs))
    }

    @Test fun onlyFourHundredDaysAreKept() {
        val saved = (0 until 450).fold(PodioSaved()) { acc, n -> PodioStats.add(acc, day.plusDays(n.toLong()), "a", 1_000) }
        assertEquals(PodioStats.DAYS_KEPT, saved.listened.size)
        assertTrue(day.plusDays(449).toString() in saved.listened)
    }

    @Test fun theTrackerCountsPlayingNotSeeking() {
        val file = java.nio.file.Files.createTempDirectory("podio-stats").resolve("library.json").toFile()
        val library = PodioLibrary(file)
        var clock = 0L
        val tracker = PodioProgressTracker(library, clockMs = { clock })
        tracker.observe("podio:episode:x", 0, playing = true, durationMs = 3_600_000)
        clock = 5_000; tracker.observe("podio:episode:x", 5_000, playing = true, durationMs = 3_600_000)
        clock = 10_000; tracker.observe("podio:episode:x", 600_000, playing = true, durationMs = 3_600_000)
        clock = 15_000; tracker.observe("podio:episode:x", 605_000, playing = false, durationMs = 3_600_000)
        tracker.close()
        assertEquals(10_000L, library.state.value.listened.values.sumOf { it.values.sum() })
    }
}
