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
}
