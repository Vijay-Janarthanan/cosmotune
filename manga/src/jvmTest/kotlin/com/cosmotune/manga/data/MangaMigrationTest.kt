package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class MangaMigrationTest {
    private fun chapters(source: String, book: String, vararg numbers: Float) =
        numbers.map { MangaChapter("$source:c$it", book, source, "Chapter $it", "/$it", number = it) }

    private val old = MangaBook("a:1", "a", "Blue Period", chapters = chapters("a", "a:1", 1f, 2f, 3f), addedAtMs = 100)
    private val new = MangaBook("b:9", "b", "Blue Period", chapters = chapters("b", "b:9", 1f, 2f, 3f, 4f), addedAtMs = 900)

    @Test fun readChaptersPlaceCollectionsAndLinksMoveToTheNewSource() {
        val state = MangaLibraryState(
            books = listOf(old), readChapters = setOf("a:c1.0", "a:c2.0"), newChapters = setOf("a:c3.0"),
            progress = mapOf(old.id to MangaProgress(old.id, "a:c3.0", 4, 20, pageOffsetPx = 30)),
            downloads = mapOf("a:c1.0" to emptyList()),
            collections = listOf(MangaCollection("x", "Favourites", setOf(old.id))),
            tracks = mapOf(old.id to listOf(MangaTrack("mangaupdates", "42", "Blue Period", 2))),
            activeBookId = old.id, activeChapterId = "a:c3.0",
        )
        val moved = state.migrated(old, new)
        assertEquals(listOf("b:9"), moved.books.map { it.id })
        assertEquals(100, moved.books.single().addedAtMs, "the series keeps the day it was added")
        assertEquals(setOf("b:c1.0", "b:c2.0"), moved.readChapters)
        assertTrue(moved.newChapters.isEmpty() && moved.downloads.isEmpty())
        val place = moved.progress.getValue("b:9")
        assertEquals("b:c3.0", place.chapterId); assertEquals(4, place.page); assertEquals(0, place.pageOffsetPx)
        assertEquals(setOf("b:9"), moved.collections.single().bookIds)
        assertEquals("42", moved.tracks.getValue("b:9").single().remoteId)
        assertEquals("b:9", moved.activeBookId); assertEquals("b:c3.0", moved.activeChapterId)
        assertEquals(MangaMigrationSummary(2, 2, 4), mangaMigrationSummary(old, new, state))
    }

    @Test fun matchesRankByTitleAndKeepAFewPerSource() {
        val hits = listOf(
            MangaBook("b:1", "b", "Blue Period"), MangaBook("b:2", "b", "Blue Period: Extra"), MangaBook("b:3", "b", "Blue"), MangaBook("b:4", "b", "Period Blue Lock"),
            MangaBook("c:1", "c", "Something else"), MangaBook("a:2", "a", "Blue Period"),
        )
        val matches = mangaMigrationMatches(old, hits)
        assertEquals("b:1", matches.first().book.id)
        assertTrue(matches.none { it.book.sourceId == "a" }, "the series' own source is not a target")
        assertTrue(matches.none { it.book.id == "c:1" })
        assertEquals(3, matches.count { it.book.sourceId == "b" })
    }

    @Test fun movingToASeriesAlreadyInTheLibraryLeavesOneCopy() {
        val state = MangaLibraryState(books = listOf(old, new))
        assertEquals(listOf("b:9"), state.migrated(old, new).books.map { it.id })
    }
}
