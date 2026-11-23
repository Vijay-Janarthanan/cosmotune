package com.cosmotune.manga.ui

import com.cosmotune.manga.data.*
import org.junit.Test
import kotlin.test.*

class MangaUpdatesViewTest {
    private val day = 86_400_000L
    private fun chapter(book: String, n: Int, at: Long) = MangaChapter("$book:$n", book, "s", "Chapter $n", "/$n", number = n.toFloat(), uploadDate = at)

    @Test fun theNextChapterIsExpectedAfterTheUsualGap() {
        val weekly = MangaBook("w", "s", "Weekly", chapters = (1..5).map { chapter("w", it, it * 7 * day) })
        assertEquals(42 * day, mangaNextRelease(weekly))
        assertNull(mangaNextRelease(weekly.copy(parserData = MangaParserBookData("", state = "FINISHED"))), "a finished series has no next chapter")
        assertNull(mangaNextRelease(weekly.copy(chapters = weekly.chapters.take(2))), "two dates are not a rhythm")
        val state = MangaLibraryState(books = listOf(weekly, MangaBook("l", "local", "Local", chapters = (1..5).map { chapter("l", it, it * day) })))
        assertEquals(listOf("w"), mangaUpcoming(state, nowMs = 40 * day).map { it.first.id })
        assertEquals("Expected in 2 days", expectedLabel(42 * day, 40 * day))
        assertEquals("Expected 3 days ago", expectedLabel(42 * day, 45 * day))
        assertEquals("Expected today", expectedLabel(42 * day, 42 * day + 1000))
    }

    @Test fun updatesGroupBySeriesNewestFirst() {
        val a = MangaBook("a", "s", "A", chapters = listOf(chapter("a", 1, 10), chapter("a", 2, 30)))
        val b = MangaBook("b", "s", "B", chapters = listOf(chapter("b", 1, 20)))
        val state = MangaLibraryState(books = listOf(a, b), newChapters = setOf("a:1", "a:2", "b:1"))
        val grouped = mangaUpdatesBySeries(state)
        assertEquals(listOf("a", "b"), grouped.map { it.first.id })
        assertEquals(2, grouped.first().second.size)
    }
}
