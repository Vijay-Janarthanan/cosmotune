package com.cosmotune.manga.ui

import com.cosmotune.manga.data.MangaBook
import com.cosmotune.manga.data.MangaChapter
import com.cosmotune.manga.data.MangaLibraryState
import com.cosmotune.manga.data.MangaPage
import com.cosmotune.manga.data.MangaProgress
import kotlin.test.Test
import kotlin.test.assertEquals

/** The library that opens on the grid (board frame 10): the counts on a cover, the Continue row and the order of the covers. */
class MangaLibraryLogicTest {
    private fun chapter(n: Int, book: String = "book", number: Float = n.toFloat()) = MangaChapter("$book-c$n", book, "src", "Chapter $n", "u$n", number)
    private val book = MangaBook("book", "src", "A series", chapters = (1..4).map { chapter(it) }, addedAtMs = 10)

    @Test fun aCoverCountsItsUnreadAndDownloadedChapters() {
        val state = MangaLibraryState(books = listOf(book), readChapters = setOf("book-c1"),
            downloads = mapOf("book-c1" to listOf(MangaPage("p")), "book-c2" to listOf(MangaPage("q"))))
        assertEquals(MangaCoverCounts(unread = 3, downloaded = 2), mangaCoverCounts(book, state))
        // A comic on this device is not "downloaded": it has nothing to download.
        assertEquals(0, mangaCoverCounts(book.copy(sourceId = "local"), state).downloaded)
    }

    @Test fun theCountsStandInTheLineUnderTheCoverNotOnIt() {
        assertEquals("3 unread · 2 downloaded", mangaCountsLine(MangaCoverCounts(unread = 3, downloaded = 2)))
        assertEquals("1 unread", mangaCountsLine(MangaCoverCounts(unread = 1, downloaded = 0)))
        assertEquals("4 downloaded", mangaCountsLine(MangaCoverCounts(unread = 0, downloaded = 4)))
        assertEquals(null, mangaCountsLine(MangaCoverCounts(unread = 0, downloaded = 0)))
    }

    @Test fun theContinueRowSaysWhereTheReaderStands() {
        val reading = MangaLibraryState(books = listOf(book), progress = mapOf("book" to MangaProgress("book", "book-c2", page = 5, pageCount = 22)))
        assertEquals("Chapter 2 · Page 6 of 22", mangaContinueLine(book, reading))
        val finished = MangaLibraryState(books = listOf(book), readChapters = setOf("book-c1", "book-c2"),
            progress = mapOf("book" to MangaProgress("book", "book-c2", page = 21, pageCount = 22)))
        assertEquals("Next: Chapter 3", mangaContinueLine(book, finished))
        assertEquals(null, mangaContinueLine(book.copy(chapters = emptyList()), reading))
    }

    @Test fun theOptionsSheetsOrderIsTheGridsOrder() {
        val other = MangaBook("other", "src", "Another", chapters = listOf(chapter(1, "other")), addedAtMs = 20)
        val state = MangaLibraryState(books = listOf(book, other), progress = mapOf("book" to MangaProgress("book", "book-c1", 0, 10, updatedAtMs = 99)))
        assertEquals(listOf("book", "other"), mangaLibraryOrder(listOf(other, book), "recent", state).map { it.id })
        assertEquals(listOf("book", "other"), mangaLibraryOrder(listOf(other, book), "title", state).map { it.id })
        assertEquals(listOf("other", "book"), mangaLibraryOrder(listOf(book, other), "added", state).map { it.id })
        assertEquals(listOf("book", "other"), mangaLibraryOrder(listOf(other, book), "unread", state).map { it.id })
    }

    @Test fun aChaptersTileShowsItsNumber() {
        assertEquals("99", chapterNumber(chapter(99), 1))
        assertEquals("4.5", chapterNumber(chapter(4, number = 4.5f), 1))
        assertEquals("7", chapterNumber(chapter(1, number = 0f), 7))
    }
}
