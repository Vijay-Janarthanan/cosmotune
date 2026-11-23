package com.cosmotune.reading.data

import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingStatsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun progressWeighsChaptersBySizeAndTimeLeftFollowsThePace() {
        val spine = listOf("a", "b", "c")
        val sizes = listOf(100L, 300L, 600L)
        assertEquals(0.1 + 0.15, ReadingProgressMath.overall(ReadingLocator("b", .5), spine, sizes), 1e-9)
        assertEquals(0.4, ReadingProgressMath.chapterEnd(ReadingLocator("b", .5), spine, sizes), 1e-9)
        assertEquals(1.0, ReadingProgressMath.chapterEnd(ReadingLocator("c"), spine, sizes))
        assertNull(ReadingProgressMath.timeFor(.5, ReadingPace(.1, 60_000)), "not enough reading yet")
        val pace = ReadingPace(.1, 600_000) // a tenth of the book in ten minutes
        assertEquals(3_000_000L, ReadingProgressMath.timeFor(.5, pace))
        assertEquals("50 min", ReadingProgressMath.duration(3_000_000))
        assertEquals("1 h 5 min", ReadingProgressMath.duration(65 * 60_000L))
        val line = ReadingProgressMath.footer(listOf(ReadingFooterItem.CHAPTER, ReadingFooterItem.BOOK_LEFT, ReadingFooterItem.PERCENT, ReadingFooterItem.CLOCK),
            "The storm", .5, .6, pace, 3_600_000L * 9 + 60_000 * 5, ZoneOffset.UTC)
        assertEquals("The storm · 50 min left in book · 50 % · 09:05", line)
        assertEquals("The storm", ReadingProgressMath.footer(listOf(ReadingFooterItem.CHAPTER, ReadingFooterItem.CHAPTER_LEFT), "The storm", .5, .6, null, 0))
    }

    @Test fun readingTimeAddsUpByDayAndBookWithAStreak() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        val book = repository.importFile("Tale.txt") { "Chapter 1\n\nOne.\n\nChapter 2\n\nTwo.\n".byteInputStream() }
        repository.recordReading(book.id, 120_000, .02)
        repository.recordReading(book.id, 60_000, .5) // a jump is time read, not pace
        val state = repository.state.value
        assertEquals(180_000L, state.readingMs[book.id])
        assertEquals(ReadingPace(.02, 120_000), state.paces[book.id])
        val today = LocalDate.now()
        assertEquals(180_000L, ReadingStatistics.week(state, today).last().second)
        assertEquals(1, ReadingStatistics.streak(state.copy(readingDays = state.readingDays + (today.minusDays(3).toString() to 600_000L)), today))
        assertEquals(2, ReadingStatistics.streak(state.copy(readingDays = state.readingDays + (today.minusDays(1).toString() to 600_000L)), today))
        repository.markFinished(book.id)
        assertEquals(1, ReadingStatistics.finishedIn(repository.state.value, today.year))
        assertEquals(book.id, ReadingStatistics.topBooks(repository.state.value).single().first.id)
    }
}
