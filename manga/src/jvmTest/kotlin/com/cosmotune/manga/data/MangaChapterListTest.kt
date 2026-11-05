package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.assertEquals

/** MG-04: how a series page lists its chapters. */
class MangaChapterListTest {
    private fun chapter(id: String, number: Float, language: String = "", branch: String? = null, volume: Int = 0) =
        MangaChapter(id, "book", "s", id, id, number, language, volume = volume, branch = branch)

    @Test fun editionsPreferTheBranchAndKeepFirstSeenOrder() {
        val book = MangaBook("book", "s", "Series", chapters = listOf(chapter("a", 1f, "en", "Team B"), chapter("b", 1f, "en"),
            chapter("c", 2f, "", "Team B"), chapter("d", 3f)))
        assertEquals(listOf("Team B", "en"), book.editions())
        assertEquals(listOf("a", "c"), book.listedChapters(edition = "Team B").map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), book.listedChapters().map { it.id }, "blank edition lists every chapter")
    }

    @Test fun orderIsByVolumeThenNumberWithTiesInSourceOrderAndReadChaptersCanBeHidden() {
        val book = MangaBook("book", "s", "Series", chapters = listOf(chapter("v2c1", 1f, volume = 2), chapter("v1c10", 10f, volume = 1),
            chapter("v1c2", 2f, volume = 1), chapter("v1c2b", 2f, volume = 1)))
        assertEquals(listOf("v1c2", "v1c2b", "v1c10", "v2c1"), book.listedChapters().map { it.id })
        assertEquals(listOf("v2c1", "v1c10", "v1c2b", "v1c2"), book.listedChapters(newestFirst = true).map { it.id })
        assertEquals(listOf("v1c2b", "v2c1"), book.listedChapters(unreadOnly = true, read = setOf("v1c2", "v1c10")).map { it.id })
        assertEquals(listOf("v1c2", "v1c2b", "v1c10", "v2c1"), book.listedChapters(read = setOf("v1c2")).map { it.id }, "read chapters stay unless hidden")
    }
}
