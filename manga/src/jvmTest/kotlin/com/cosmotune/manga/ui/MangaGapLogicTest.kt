package com.cosmotune.manga.ui

import com.cosmotune.manga.data.*
import org.junit.Test
import kotlin.test.*

class MangaGapLogicTest {
    @Test fun plainMarginsAreCutButArtworkAndThinMarginsStay() {
        val width = 100; val height = 100
        val white = 0xFFFFFFFF.toInt(); val ink = 0xFF101010.toInt()
        val page = IntArray(width * height) { i -> val x = i % width; val y = i / width; if (x in 20 until 80 && y in 10 until 90) ink else white }
        assertEquals(MangaPageBounds(20, 10, 80, 90), mangaContentBounds(page, width, height))
        val full = IntArray(width * height) { ink }
        assertEquals(MangaPageBounds(0, 0, width, height), mangaContentBounds(full, width, height), "a black page with no margin")
        val coloured = IntArray(width * height) { 0xFF8040A0.toInt() }
        assertEquals(MangaPageBounds(0, 0, width, height), mangaContentBounds(coloured, width, height), "a coloured corner is artwork")
        val almostWhite = IntArray(width * height) { i -> if (i % width in 1 until 99) ink else white }
        assertEquals(MangaPageBounds(0, 0, width, height), mangaContentBounds(almostWhite, width, height), "a one-pixel margin is not worth cutting")
        val blank = IntArray(width * height) { white }
        assertEquals(MangaPageBounds(0, 0, width, height), mangaContentBounds(blank, width, height), "a blank page is shown as it is")
    }

    @Test fun spreadsPairPagesWithTheCoverAlone() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), mangaSpreads(6, coverAlone = true))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), mangaSpreads(5, coverAlone = false))
        assertTrue(mangaSpreads(0, true).isEmpty())
    }

    @Test fun theFeedKeepsTheListenersOrderAndHidesShelves() {
        val sources = listOf("a", "b", "c").map { MangaSourceConfig(it, name = it.uppercase()) }
        val state = MangaLibraryState(sources = sources, feedOrder = listOf("c", "a"), feedHidden = setOf("a"))
        assertEquals(listOf("c", "b"), mangaFeedSources(state).map { it.id }, "saved order first, new sources after, hidden ones out")
        assertEquals(listOf("a", "c", "b"), mangaFeedMove(listOf("c", "a", "b"), "a", -1))
        assertEquals(listOf("c", "a", "b"), mangaFeedMove(listOf("c", "a", "b"), "c", -1), "the first cannot move up")
    }

    @Test fun forYouLeavesOutTheLibraryAndSeriesNotWanted() {
        val owned = MangaBook("x:1", "x", "Owned")
        val state = MangaLibraryState(books = listOf(owned), notInterested = setOf("x:3"))
        val found = listOf(owned, MangaBook("x:2", "x", "New"), MangaBook("x:3", "x", "Nope"), MangaBook("x:2", "x", "New"))
        assertEquals(listOf("x:2"), mangaForYou(found, state).map { it.id })
    }

    @Test fun theQueueLineSaysWhatIsRunningWaitingAndFailed() {
        val book = MangaBook("b", "s", "Series")
        fun item(id: String, error: String? = null, done: Int = 0, total: Int = 0) = MangaQueued(book, MangaChapter(id, "b", "s", "Chapter $id", "/$id"), done, total, error)
        val items = listOf(item("1", done = 3, total = 10), item("2"), item("3", error = "Failed"))
        assertEquals("Downloading Chapter 1: 3 of 10 pages · 1 more waiting · 1 failed", mangaQueueLine(items, MangaQueueState(items, running = "1")))
        assertEquals("Paused · 2 waiting · 1 failed", mangaQueueLine(items, MangaQueueState(items, paused = true)))
    }

    @Test fun tapZoneLayoutsTurnTheRightWay() {
        val ltr = MangaReaderMode.PAGES_LTR
        assertEquals(ReaderTap.NEXT, readerTap(90f, 50f, 100f, 100f, ltr, MangaTapZones.THIRDS, false))
        assertEquals(ReaderTap.PREVIOUS, readerTap(90f, 50f, 100f, 100f, ltr, MangaTapZones.THIRDS, true), "inverted")
        assertEquals(ReaderTap.NEXT, readerTap(10f, 50f, 100f, 100f, MangaReaderMode.PAGES_RTL, MangaTapZones.THIRDS, false), "right to left mirrors")
        assertEquals(ReaderTap.PREVIOUS, readerTap(50f, 10f, 100f, 100f, ltr, MangaTapZones.L_SHAPED, false), "L shape: the top band goes back")
        assertEquals(ReaderTap.NEXT, readerTap(50f, 90f, 100f, 100f, ltr, MangaTapZones.L_SHAPED, false), "L shape: the bottom band goes on")
        assertEquals(ReaderTap.MENU, readerTap(50f, 10f, 100f, 100f, ltr, MangaTapZones.KINDLE, false), "Kindle: the top band is the menu")
        assertEquals(ReaderTap.NEXT, readerTap(60f, 80f, 100f, 100f, ltr, MangaTapZones.KINDLE, false))
        assertEquals(ReaderTap.NEXT, readerTap(10f, 50f, 100f, 100f, ltr, MangaTapZones.EDGE, false), "Edges: both sides go on")
        assertEquals(ReaderTap.PREVIOUS, readerTap(50f, 90f, 100f, 100f, ltr, MangaTapZones.EDGE, false))
        assertEquals(ReaderTap.MENU, readerTap(90f, 50f, 100f, 100f, ltr, MangaTapZones.OFF, false))
        assertEquals(ReaderTap.MENU, readerTap(90f, 50f, 100f, 100f, MangaReaderMode.WEBTOON, MangaTapZones.THIRDS, false))
    }

    @Test fun libraryFiltersCombineAndNewSortsOrder() {
        fun book(id: String, vararg chapters: Pair<String, Long>, state: String? = null) = MangaBook(id, "s", id,
            chapters = chapters.map { MangaChapter(it.first, id, "s", it.first, "/", uploadDate = it.second) }, parserData = MangaParserBookData("", state = state))
        val a = book("a", "a1" to 5L, "a2" to 50L); val b = book("b", "b1" to 10L, state = "FINISHED"); val c = book("c")
        val state = MangaLibraryState(books = listOf(a, b, c), readChapters = setOf("a1", "b1"), downloads = mapOf("a2" to emptyList()),
            tracks = mapOf("b" to listOf(MangaTrack("mangaupdates", "1", "B"))))
        val all = listOf(a, b, c)
        assertEquals(listOf("a"), mangaLibraryFilter(all, setOf(MangaLibraryFilters.UNREAD, MangaLibraryFilters.DOWNLOADED), state).map { it.id })
        assertEquals(listOf("a", "b"), mangaLibraryFilter(all, setOf(MangaLibraryFilters.STARTED), state).map { it.id })
        assertEquals(listOf("b"), mangaLibraryFilter(all, setOf(MangaLibraryFilters.COMPLETED, MangaLibraryFilters.TRACKED), state).map { it.id })
        assertEquals(listOf("a", "b", "c"), mangaLibraryOrder(all, "latest", state).map { it.id })
        assertEquals(listOf("a", "b", "c"), mangaLibraryOrder(all, "chapters", state).map { it.id })
    }

    @Test fun updatesSkipFinishedReadSeriesAndTakeSourcesInTurn() {
        fun book(id: String, source: String, finished: Boolean = false, read: Boolean = false) = MangaBook(id, source, id,
            chapters = listOf(MangaChapter("$id:1", id, source, "1", "/")), parserData = MangaParserBookData("", state = if (finished) "FINISHED" else "ONGOING"))
        val books = listOf(book("a1", "a"), book("a2", "a"), book("a3", "a"), book("b1", "b"), book("done", "b", finished = true), book("off", "c"))
        val state = MangaLibraryState(books = books, readChapters = setOf("done:1"))
        assertEquals(listOf("a1", "b1", "a2", "a3"), mangaSeriesToUpdate(state, setOf("a", "b")).map { it.id })
        assertTrue(mangaSeriesToUpdate(state.copy(updateSkipFinished = false), setOf("a", "b")).any { it.id == "done" })
    }
}
