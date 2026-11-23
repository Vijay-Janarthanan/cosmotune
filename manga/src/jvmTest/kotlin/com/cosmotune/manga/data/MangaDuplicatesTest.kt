package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class MangaDuplicatesTest {
    private fun book(id: String, source: String, title: String, vararg numbers: Int) =
        MangaBook(id, source, title, chapters = numbers.map { MangaChapter("$id:$it", id, source, "Ch $it", "/$it", number = it.toFloat()) })

    @Test fun theSameTitleTwiceIsADuplicateAndMergingKeepsOne() {
        val a = book("a", "x", "Blue Period!", 1, 2, 3)
        val b = book("b", "y", "blue period", 1, 2, 3, 4)
        val c = book("c", "y", "Other", 1)
        val state = MangaLibraryState(books = listOf(a, b, c), readChapters = setOf("a:1", "a:2", "b:4"),
            collections = listOf(MangaCollection("k", "Fav", setOf("a"))), progress = mapOf("b" to MangaProgress("b", "b:4", 0, 10)))
        val groups = mangaDuplicates(state)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().map { it.id }, "the one read further comes first")
        val merged = state.merged(b, groups.single())
        assertEquals(listOf("b", "c"), merged.books.map { it.id }.sorted())
        assertEquals(setOf("b:1", "b:2", "b:4"), merged.readChapters)
        assertEquals(setOf("b"), merged.collections.single().bookIds)
        assertEquals("b:4", merged.progress.getValue("b").chapterId, "the kept series keeps its own place")
    }
}
