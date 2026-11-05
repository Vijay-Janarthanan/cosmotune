package com.cosmotune.music

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.stats.DayStats
import com.cosmotune.music.data.stats.ListeningStats
import com.cosmotune.music.data.stats.NameEntry
import com.cosmotune.music.data.stats.ReplayPeriod
import com.cosmotune.music.data.stats.StoredBucket
import com.cosmotune.music.data.stats.TrackEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * BC-18: the weekly Replay. A week is Monday to today and may start in the month before, so it is read from the per-day
 * breakdown each entry carries, never from a month's totals. Uses a temporary folder and a fixed clock; nothing on the
 * device or in the real app data is touched.
 */
class ReplayWeekTest {
    private lateinit var folder: File

    private fun at(date: String, hour: Int = 12): Clock =
        Clock.fixed(LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun song(id: String, artist: String) = Song(videoId = id, title = "Title $id", artist = artist, thumbnailUrl = null)

    @Before fun setUp() {
        folder = Files.createTempDirectory("replay-week").toFile()
        ListeningStats.clock = at("2026-10-01")
        ListeningStats.init(folder)
    }

    @After fun tearDown() {
        ListeningStats.clock = Clock.systemDefaultZone()
        folder.deleteRecursively()
    }

    /** September as an earlier build of this code filed it: daily data on each entry, the month's day totals beside it. */
    private fun september() = StoredBucket(
        month = "2026-09",
        tracks = listOf(
            TrackEntry(id = "a", title = "Title a", artist = "Ann", ms = 180_000, plays = 2,
                days = mutableMapOf("2026-09-27" to DayStats(60_000, 1), "2026-09-29" to DayStats(120_000, 1))),
            TrackEntry(id = "b", title = "Title b", artist = "Ben", ms = 300_000, plays = 1,
                days = mutableMapOf("2026-09-26" to DayStats(300_000, 1))),
        ),
        artists = listOf(
            NameEntry(name = "Ann", ms = 180_000, plays = 2,
                days = mutableMapOf("2026-09-27" to DayStats(60_000, 1), "2026-09-29" to DayStats(120_000, 1))),
            NameEntry(name = "Ben", ms = 300_000, plays = 1, days = mutableMapOf("2026-09-26" to DayStats(300_000, 1))),
        ),
        days = mapOf(26 to 300_000L, 27 to 60_000L, 29 to 120_000L),
    )

    @Test fun aWeekThatStartsInTheMonthBeforeCountsOnlyItsOwnDays() = runBlocking {
        ListeningStats.importAll(listOf(september()))
        // Thursday 1 October: the week began on Monday 28 September.
        ListeningStats.record(song("a", "Ann"), playedMs = 30_000, countsAsPlay = true)

        val week = ListeningStats.summary(ReplayPeriod.THIS_WEEK)
        assertEquals(listOf("a"), week.songs.map { it.song.videoId })
        assertEquals(150_000L, week.songs.single().ms)
        assertEquals(2, week.songs.single().plays)
        assertEquals(150_000L, week.totalMs)
        assertEquals(listOf("Ann" to 150_000L), week.artists.map { it.title to it.ms })
        assertEquals("the busiest day of the week, not of the month", "2026-09-29", week.busiestDay)
        assertEquals(120_000L, week.busiestDayMs)
        assertEquals("2026-09", week.since)

        val month = ListeningStats.summary(ReplayPeriod.THIS_MONTH)
        assertEquals(listOf("a" to 30_000L), month.songs.map { it.song.videoId to it.ms })
        val all = ListeningStats.summary(ReplayPeriod.ALL_TIME)
        assertEquals("all time still adds the month totals", 510_000L, all.totalMs)
    }

    @Test fun anEntryFiledBeforeTheDailyBreakdownIsLeftOutOfTheWeekButNotOutOfTheMonth() = runBlocking {
        ListeningStats.importAll(listOf(StoredBucket(
            month = "2026-10",
            tracks = listOf(TrackEntry(id = "old", title = "Old", artist = "Cy", ms = 999_000, plays = 9)),
            artists = listOf(NameEntry(name = "Cy", ms = 999_000, plays = 9)),
        )))
        ListeningStats.record(song("new", "Di"), playedMs = 10_000, countsAsPlay = false)

        val week = ListeningStats.summary(ReplayPeriod.THIS_WEEK)
        assertEquals(listOf("new" to 10_000L), week.songs.map { it.song.videoId to it.ms })
        assertEquals(listOf("Di"), week.artists.map { it.title })

        val month = ListeningStats.summary(ReplayPeriod.THIS_MONTH)
        assertEquals(setOf("old", "new"), month.songs.map { it.song.videoId }.toSet())
    }

    @Test fun recordingFilesEachSampleUnderItsOwnDay() = runBlocking {
        ListeningStats.record(song("x", "Ed"), playedMs = 40_000, countsAsPlay = true)
        ListeningStats.clock = at("2026-10-02")
        ListeningStats.record(song("x", "Ed"), playedMs = 5_000, countsAsPlay = false)

        val stored = ListeningStats.exportAll().single()
        val track = stored.tracks.single()
        assertEquals(mapOf("2026-10-01" to DayStats(40_000, 1), "2026-10-02" to DayStats(5_000, 0)), track.days)
        assertEquals(mapOf("2026-10-01" to DayStats(40_000, 1), "2026-10-02" to DayStats(5_000, 0)), stored.artists.single().days)
        assertEquals(mapOf(1 to 40_000L, 2 to 5_000L), stored.days)
    }

    @Test fun nothingPlayedThisWeekIsAnEmptyReplayEvenWithAFullMonthBehindIt() = runBlocking {
        ListeningStats.importAll(listOf(september()))
        ListeningStats.clock = at("2026-10-12") // a Monday two weeks on
        val week = ListeningStats.summary(ReplayPeriod.THIS_WEEK)
        assertEquals(true, week.isEmpty)
        assertNull(week.busiestDay)
    }

    @Test fun theWeekRunsFromMondayToToday() {
        assertEquals(LocalDate.parse("2026-09-28") to LocalDate.parse("2026-10-04"), ReplayPeriod.THIS_WEEK.range(LocalDate.parse("2026-10-04")))
        assertEquals(LocalDate.parse("2026-10-05") to LocalDate.parse("2026-10-05"), ReplayPeriod.THIS_WEEK.range(LocalDate.parse("2026-10-05")))
        val today = LocalDate.parse("2026-10-01")
        assertEquals(true, ReplayPeriod.THIS_WEEK.covers(java.time.YearMonth.parse("2026-09"), today))
        assertEquals(false, ReplayPeriod.THIS_WEEK.covers(java.time.YearMonth.parse("2026-08"), today))
    }
}
