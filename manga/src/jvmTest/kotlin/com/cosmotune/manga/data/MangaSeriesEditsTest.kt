package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaSeriesEditsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aChosenTitleAndCoverSurviveARefreshUntilReset() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val book = MangaBook("s:1", "s", "Source title", chapters = listOf(MangaChapter("c1", "s:1", "s", "One", "/1")))
        store.save(book)
        store.retitle(book.id, "My title")
        store.refreshed(book.copy(title = "Renamed upstream"))
        assertEquals("My title", store.state.value.books.single().title)
        val page = temporary.newFile("page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        store.cover(book.id, page)
        val cover = java.io.File(store.state.value.customCovers.getValue(book.id))
        assertContentEquals(byteArrayOf(1, 2, 3), cover.readBytes())
        store.cover(book.id, null)
        assertFalse(cover.exists()); assertTrue(store.state.value.customCovers.isEmpty())
        store.retitle(book.id, "")
        store.refreshed(book.copy(title = "Renamed upstream"))
        assertEquals("Renamed upstream", store.state.value.books.single().title)
    }
}
