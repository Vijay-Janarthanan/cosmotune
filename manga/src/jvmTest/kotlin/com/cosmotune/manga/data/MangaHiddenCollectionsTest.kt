package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaHiddenCollectionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hiddenCollectionsLeaveAllAndNewSeriesJoinTheDefault() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val secret = store.createCollection("Later")
        val reading = store.createCollection("Reading")
        store.save(MangaBook("a", "s", "A"))
        store.assignCollection("a", secret, true)
        store.hideCollection(secret, true)
        store.defaultCollection(reading)
        store.save(MangaBook("b", "s", "B"))
        store.save(MangaBook("b", "s", "B again"))
        val state = store.state.value
        assertEquals(listOf("b"), state.collectionBooks("").map { it.id }, "a series in a hidden collection is not in All")
        assertEquals(listOf("a"), state.collectionBooks(secret).map { it.id }, "choosing the collection shows it")
        assertEquals(setOf("b"), state.collections.first { it.id == reading }.bookIds)
        store.deleteCollection(reading)
        assertEquals("", store.state.value.defaultCollection)
    }

    @Test fun hiddenTravelsWithSync() {
        val state = MangaLibraryState(books = listOf(MangaBook("s:1", "s", "A")), collections = listOf(MangaCollection("c", "Later", setOf("s:1"), hidden = true)))
        val other = SyncMangaLibrary.apply(MangaLibraryState(), SyncMangaLibrary.snapshot(state), emptySet())
        assertTrue(other.collections.single().hidden)
    }
}
