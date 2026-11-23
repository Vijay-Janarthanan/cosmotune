package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class MangaNextChapterTest {
    private fun chapter(id: String, n: Float) = MangaChapter(id, "b", "s", "Ch $n", "/$id", number = n)
    private val book = MangaBook("b", "s", "B", chapters = listOf(chapter("1", 1f), chapter("2", 2f), chapter("2b", 2f), chapter("3", 3f), chapter("4", 4f)))

    @Test fun nextSkipsASecondUploadOfTheSameNumberAndReadOnesWhenAsked() {
        assertEquals("2", book.nextChapter(chapter("1", 1f), emptySet(), skipRead = false)?.id)
        assertEquals("3", book.nextChapter(chapter("2", 2f), emptySet(), skipRead = false)?.id, "2b is the same chapter again")
        assertEquals("4", book.nextChapter(chapter("2", 2f), setOf("3"), skipRead = true)?.id)
        assertEquals("3", book.nextChapter(chapter("2", 2f), setOf("3"), skipRead = false)?.id)
        assertNull(book.nextChapter(chapter("4", 4f), emptySet(), skipRead = false))
    }
}
