package com.cosmotune.manga.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaCollectionsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun collectionsPersistAssignFilterRenameAndDeleteWithoutDeletingSeries() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        store.save(MangaBook("one", "local", "One")); store.save(MangaBook("two", "local", "Two"))
        val id = store.createCollection("  Favourites  ")
        store.assignCollection("one", id, true)
        assertEquals(listOf("one"), store.state.value.collectionBooks(id).map { it.id })
        assertEquals(listOf("two"), store.state.value.collectionBooks("unassigned").map { it.id })
        assertFailsWith<IllegalArgumentException> { store.createCollection("favourites") }
        store.renameCollection(id, "Finished")
        assertEquals("Finished", MangaStore(store.root).state.value.collections.single().name)
        store.assignCollection("one", id, false)
        assertTrue(store.state.value.collectionBooks(id).isEmpty())
        store.assignCollection("two", id, true); store.remove("two")
        assertTrue(store.state.value.collections.single().bookIds.isEmpty())
        store.deleteCollection(id)
        assertEquals("one", store.state.value.books.single().id)
        assertTrue(store.state.value.collections.isEmpty())
    }

    @Test fun oldLibrariesDefaultToNoCollectionsOrBackgroundWorkAndBackupMergesMetadataOnly() = runBlocking<Unit> {
        val legacy = MangaStore(temporary.newFolder())
        File(legacy.root, "library.json").writeText("""{"version":1,"books":[],"sources":[]} """)
        assertEquals(0, MangaStore(legacy.root).state.value.updateIntervalHours)
        val original = MangaStore(temporary.newFolder())
        val chapter = MangaChapter("c1", "remote", "fixture", "One", "chapter/1")
        original.save(MangaBook("remote", "fixture", "Online", chapters = listOf(chapter.copy(sourceMemo = JsonObject(mapOf("token" to JsonPrimitive("private-chapter-memo"))))),
            sourceMemo = JsonObject(mapOf("token" to JsonPrimitive("private-book-memo")))))
        original.save(MangaBook("local", "local", "Local", chapters = listOf(chapter.copy(id = "local-c", bookId = "local", sourceId = "local", archiveFile = "/private/comic.cbz"))))
        val group = original.createCollection("Favourites")
        original.assignCollection("remote", group, true); original.assignCollection("local", group, true)
        original.progress(MangaProgress("remote", "c1", 1, 4))
        original.source(MangaSourceConfig("fixture", name = "Private", settings = mapOf("password" to "not-exported")))
        val encoded = MangaLibraryBackup.from(original.state.value).encode()
        assertFalse(encoded.toString(Charsets.UTF_8).contains("not-exported"))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("/private"))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("private-book-memo"))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("private-chapter-memo"))
        val destination = MangaStore(temporary.newFolder())
        destination.save(MangaBook("local", "local", "Reimported"))
        MangaLibraryBackup.decode(encoded).merge(destination)
        assertEquals(setOf("remote", "local"), destination.state.value.books.map { it.id }.toSet())
        assertEquals(setOf("remote", "local"), destination.state.value.collections.single().bookIds)
        assertTrue(destination.state.value.sources.isEmpty()); assertTrue(destination.state.value.downloads.isEmpty())
        assertEquals(0, destination.state.value.updateIntervalHours)
        assertEquals(1, destination.state.value.progress["remote"]?.page)
        assertEquals(destination.state.value, MangaStore(destination.root).state.value)
    }

    @Test fun backupCannotIntroduceLocalArchivePathsAndMergePreservesExistingProgress() = runBlocking<Unit> {
        val malicious = MangaLibraryBackup(books = listOf(MangaBook("bad", "remote", "Bad", chapters = listOf(MangaChapter("c", "bad", "remote", "Bad", "", archiveFile = "/etc/passwd")))))
        assertFailsWith<IllegalArgumentException> { MangaLibraryBackup.decode(malicious.encode()) }
        assertFailsWith<IllegalArgumentException> { MangaLibraryBackup.decode(MangaLibraryBackup(collections = listOf(MangaCollection("unassigned", "Reserved"))).encode()) }
        val store = MangaStore(temporary.newFolder())
        val chapter = MangaChapter("c", "book", "source", "Chapter", "")
        store.save(MangaBook("book", "source", "Current", chapters = listOf(chapter)))
        store.progress(MangaProgress("book", "c", 4, 10))
        MangaLibraryBackup(books = listOf(MangaBook("book", "source", "Old", chapters = listOf(chapter))), progress = mapOf("book" to MangaProgress("book", "c", 1, 10))).merge(store)
        assertEquals("Current", store.state.value.books.single().title)
        assertEquals(4, store.state.value.progress["book"]?.page)
    }
}
