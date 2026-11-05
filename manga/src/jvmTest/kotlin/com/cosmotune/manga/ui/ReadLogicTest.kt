package com.cosmotune.manga.ui

import com.cosmotune.manga.data.MangaBook
import com.cosmotune.manga.data.MangaChapter
import com.cosmotune.manga.data.MangaLibraryState
import com.cosmotune.manga.data.MangaProgress
import com.cosmotune.manga.data.MangaReaderMode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadLogicTest {
    private val utc = ZoneOffset.UTC
    private fun chapter(n: Int, language: String = "en", date: Long = 0) = MangaChapter("c$n", "book", "src", "Chapter $n", "u$n", n.toFloat(), language, uploadDate = date)
    private val book = MangaBook("book", "src", "A series", chapters = listOf(chapter(1), chapter(2), chapter(3)))

    @Test fun aSeriesNeverReadStartsAtItsFirstChapter() {
        val next = mangaContinue(book, MangaLibraryState(books = listOf(book)))
        assertEquals("c1", next.chapter?.id)
        assertEquals("Start reading", next.label)
    }

    @Test fun aChapterInProgressIsContinuedByItsNumber() {
        val state = MangaLibraryState(books = listOf(book), progress = mapOf("book" to MangaProgress("book", "c2", page = 3, pageCount = 20)))
        val next = mangaContinue(book, state)
        assertEquals("c2", next.chapter?.id)
        assertEquals("Continue chapter 2", next.label)
    }

    @Test fun afterAFinishedChapterTheNextOneOfTheSameEditionFollows() {
        val mixed = book.copy(chapters = book.chapters + chapter(4, language = "ta"))
        val state = MangaLibraryState(books = listOf(mixed), readChapters = setOf("c1", "c2", "c3"),
            progress = mapOf("book" to MangaProgress("book", "c3", page = 19, pageCount = 20)))
        // Chapter 4 is the Tamil edition, not the next English one: the first unread chapter is offered instead.
        assertEquals("c4", mangaContinue(mixed, state).chapter?.id)
        val english = book.copy(chapters = book.chapters + chapter(4))
        val englishState = state.copy(books = listOf(english), progress = mapOf("book" to MangaProgress("book", "c2", page = 19, pageCount = 20)))
        assertEquals("Continue chapter 3", mangaContinue(english, englishState).label)
    }

    @Test fun everythingReadOffersToReadAgainAndALocalComicIsNeverJustRead() {
        val state = MangaLibraryState(books = listOf(book), readChapters = setOf("c1", "c2", "c3"))
        assertEquals("Read again", mangaContinue(book, state).label)
        val local = MangaBook("local", "local", "Evening story", chapters = listOf(MangaChapter("local:chapter", "local", "local", "Read", "")))
        val reading = MangaLibraryState(books = listOf(local), progress = mapOf("local" to MangaProgress("local", "local:chapter", 1, 3)))
        assertEquals("Continue reading", mangaContinue(local, reading).label)
        assertEquals("Refresh chapters", mangaContinue(book.copy(chapters = emptyList()), state).label)
    }

    @Test fun updatesAreTheNewChaptersByDayNewestFirst() {
        val today = LocalDate.of(2026, 11, 4).atStartOfDay(utc).toInstant().toEpochMilli() + 3_600_000
        val yesterday = today - 86_400_000
        val series = book.copy(chapters = listOf(chapter(1, date = yesterday), chapter(2, date = today), chapter(3)))
        val state = MangaLibraryState(books = listOf(series), newChapters = setOf("c1", "c2", "c3"))
        val days = mangaUpdateDays(state, nowMs = today, zone = utc)
        assertEquals(listOf("Today", "Yesterday", "Earlier"), days.map { it.label })
        assertEquals(listOf("c2"), days.first().entries.map { it.chapter.id })
        assertTrue(mangaUpdates(state.copy(newChapters = emptySet())).isEmpty())
    }

    @Test fun historyGroupsTheLastChapterOfEachSeriesByDay() {
        val now = LocalDate.of(2026, 11, 4).atTime(12, 0).toInstant(utc).toEpochMilli()
        val other = MangaBook("other", "src", "Other", chapters = listOf(MangaChapter("o1", "other", "src", "One", "o1", 1f)))
        val state = MangaLibraryState(books = listOf(book, other), progress = mapOf(
            "book" to MangaProgress("book", "c2", 0, 10, updatedAtMs = now - 60_000),
            "other" to MangaProgress("other", "o1", 0, 10, updatedAtMs = now - 3 * 86_400_000L),
            "gone" to MangaProgress("gone", "x", 0, 10, updatedAtMs = now),
        ))
        val days = mangaHistory(state, nowMs = now, zone = utc)
        assertEquals(2, days.size, "a series no longer in the library is left out")
        assertEquals("Today", days[0].label)
        assertEquals("c2", days[0].entries.single().chapter?.id)
        assertTrue(days[1].label != "Today" && days[1].label != "Yesterday", "three days ago is named by its date")
    }

    @Test fun theTapZonesTurnPagesAtTheEdgesAndMirrorForRightToLeft() {
        assertEquals(ReaderTap.PREVIOUS, readerTap(10f, 300f, MangaReaderMode.PAGES_LTR))
        assertEquals(ReaderTap.NEXT, readerTap(290f, 300f, MangaReaderMode.PAGES_LTR))
        assertEquals(ReaderTap.MENU, readerTap(150f, 300f, MangaReaderMode.PAGES_LTR))
        assertEquals(ReaderTap.NEXT, readerTap(10f, 300f, MangaReaderMode.PAGES_RTL))
        assertEquals(ReaderTap.PREVIOUS, readerTap(290f, 300f, MangaReaderMode.PAGES_RTL))
        assertEquals(ReaderTap.MENU, readerTap(10f, 300f, MangaReaderMode.WEBTOON), "a webtoon scrolls; a tap only shows the chrome")
        assertEquals(ReaderTap.MENU, readerTap(10f, 0f, MangaReaderMode.PAGES_LTR))
    }

    @Test fun libraryLinesBadgesAndLabels() {
        val state = MangaLibraryState(books = listOf(book), readChapters = setOf("c1"), newChapters = setOf("c3"))
        assertEquals("2 unread · 1 new", mangaLibraryLine(book, state))
        assertEquals("Caught up", mangaLibraryLine(book, state.copy(readChapters = setOf("c1", "c2", "c3"))))
        assertEquals(null, mangaLibraryLine(book.copy(chapters = emptyList()), state))
        assertEquals("ES", titleInitials("  Evening   story time"))
        assertEquals("chapter 4.5", chapterName(chapter(4).copy(number = 4.5f)))
        assertEquals("Right to left", readerModeLabel(MangaReaderMode.PAGES_RTL))
    }

    @Test fun chapterLinesShowThePositionOnlyWhileTheChapterIsUnread() {
        val progress = MangaProgress("book", "c2", 4, 12)
        assertEquals("Page 5 of 12 · en", chapterSubtitle(chapter(2), progress, read = false, zone = utc))
        assertEquals("en", chapterSubtitle(chapter(2), progress, read = true, zone = utc))
        assertEquals("en", chapterSubtitle(chapter(1), progress, read = false, zone = utc))
    }

    @Test fun tabsMapEveryChannelValueOntoTheirMode() {
        assertEquals(listOf("Home", "Browse", "Updates", "Library"), MangaTabs.all)
        assertEquals(listOf("Home", "Browse", "Bookmarks", "Library"), BookTabs.all)
        assertEquals(MangaTabs.BROWSE, MangaTabs.of("Discover"))
        assertEquals(MangaTabs.HOME, MangaTabs.of("Bookmarks"))
        assertEquals(BookTabs.BOOKMARKS, BookTabs.of("Updates"))
        assertEquals(BookTabs.HOME, BookTabs.of("History"))
        assertEquals(BookTabs.BROWSE, BookTabs.of("Discover"))
        assertEquals("just now", agoLabel(1_000, 30_000))
        assertEquals("2 h ago", agoLabel(0, 2 * 3_600_000L + 5))
        assertEquals("3 days ago", agoLabel(0, 3 * 86_400_000L))
    }
}
