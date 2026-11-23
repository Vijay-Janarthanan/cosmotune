package com.cosmotune.listening

import java.time.LocalDate
import kotlin.test.*

class ListeningActivityTest {
    @Test fun windowsIncludeSilentDaysAndCrossLeapYearsWithoutInventingListening() {
        val days = ListeningActivity.window(mapOf("2024-02-29" to 60_000, "2024-03-01" to -5, "undated" to 500),
            LocalDate.parse("2024-03-02"), 4)
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01", "2024-03-02"), days.map { it.date.toString() })
        assertEquals(listOf(0L, 60_000L, 0L, 0L), days.map { it.listenedMs })
    }
    @Test fun streaksAreCalendarConsecutiveAndIgnoreZeroTotalsAndMalformedLegacyKeys() {
        assertEquals(3, ListeningActivity.longestStreak(mapOf("2026-01-01" to 5, "2026-01-02" to 0,
            "2026-01-03" to 5, "2026-01-04" to 7, "2026-01-05" to 8, "legacy" to 90)))
        assertEquals(0, ListeningActivity.longestStreak(emptyMap()))
    }

    @Test fun weekdaysAndSourcesAddUp() {
        // 2026-11-23 is a Monday.
        val week = ListeningActivity.byWeekday(mapOf("2026-11-23" to 60_000L, "2026-11-30" to 30_000L, "2026-11-29" to 10_000L, "bad" to 5L))
        kotlin.test.assertEquals(listOf(90_000L, 0L, 0L, 0L, 0L, 0L, 10_000L), week)
        val split = ListeningActivity.bySource(listOf("abcdefghijk" to 50L, "local:/m/a.flac" to 70L, "podio:x" to 10L, "subsonic:s:1" to 5L, "zzzzzzzzzzz" to 30L)) { it.startsWith("subsonic:") }
        kotlin.test.assertEquals(listOf("YouTube Music" to 80L, "This device" to 70L, "Radio and podcasts" to 10L, "Your servers" to 5L), split)
    }
}
