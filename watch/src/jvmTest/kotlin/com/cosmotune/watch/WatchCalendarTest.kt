package com.cosmotune.watch

import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchCalendarTest {
    @Test fun datesMoveAcrossMonthsYearsAndLeapDays() {
        assertEquals("2026-12-03", WatchCalendar.shift("2026-11-23", 10))
        assertEquals("2024-02-29", WatchCalendar.shift("2024-03-01", -1))
        assertEquals("2027-01-01", WatchCalendar.shift("2026-12-31", 1))
        assertEquals("2026-11-16", WatchCalendar.shift("2026-11-23", -7))
    }

    @Test fun followedSeriesGiveTheirEpisodesAroundTodaySoonestFirst() = runBlocking<Unit> {
        val repository = FakeWatchRepository()
        val series = repository.title(WatchKey.tmdbSeries(101))!!.summary
        repository.setInMyList(series, true)
        val entries = repository.calendar("2022-03-05")
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.date in "2022-02-26".."2022-05-04" && it.summary.type == com.cosmotune.watch.model.TitleType.SERIES })
        assertEquals(entries.map { it.date }, entries.map { it.date }.sorted())
        assertTrue(entries.filter { it.date <= "2022-03-05" }.all { it.isNew })
        assertTrue(entries.filter { it.date > "2022-03-05" }.none { it.isNew })
    }

    @Test fun nothingFollowedMeansAnEmptyCalendar() = runBlocking<Unit> {
        val repository = FakeWatchRepository()
        repository.continueWatching.value.forEach { repository.clearProgress(it.key) }
        assertTrue(repository.calendar("2022-03-05").isEmpty())
    }
}
