package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class MihonExportTest {
    @Test fun anExportedBackupReadsBackWithItsReadChaptersAndCategories() {
        val config = MangaSourceConfig("mihon:org.example:111", "mihon", "Example", "en")
        val id = "${config.id}:series:${MihonIds.key("/m/1")}"
        val chapter = MangaChapter("${config.id}:chapter:${MihonIds.key("/c/1")}", id, config.id, "One", "/c/1", 1f)
        val book = MangaBook(id, config.id, "Series", "/m/1", chapters = listOf(chapter))
        val state = MangaLibraryState(books = listOf(book), sources = listOf(config), readChapters = setOf(chapter.id),
            collections = listOf(MangaCollection("k", "Reading", setOf(id))))
        val bytes = MihonBackupImport.export(state)
        assertTrue(MihonBackupImport.looksLikeOne(bytes))
        val back = MihonBackupImport.read(bytes, listOf(config))
        assertEquals(listOf(id), back.books.map { it.id })
        assertEquals(setOf(chapter.id), back.read)
        assertEquals(setOf(id), back.collections.getValue("Reading"))
    }
}
