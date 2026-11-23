@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.cosmotune.manga.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MihonBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun packed(backup: TachiBackup): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(ProtoBuf.encodeToByteArray(backup)) }
    }.toByteArray()

    private val backup = TachiBackup(
        manga = listOf(
            TachiManga(source = 111, url = "/manga/one", title = "One", author = "A, B", thumbnailUrl = "https://img/one.jpg", dateAdded = 5,
                chapters = listOf(TachiChapter("/c/1", "Ch. 1", read = true, chapterNumber = 1f), TachiChapter("/c/2", "Ch. 2", lastPageRead = 6, chapterNumber = 2f)),
                categories = listOf(1), history = listOf(TachiHistory("/c/1", 10), TachiHistory("/c/2", 20))),
            TachiManga(source = 222, url = "/manga/two", title = "Two", chapters = listOf(TachiChapter("/x/1", "First", read = true, chapterNumber = 1f))),
            TachiManga(source = 222, url = "/manga/gone", title = "Not in library", favorite = false),
        ),
        categories = listOf(TachiCategory("Reading", 1)),
        sources = listOf(TachiSource("MangaDex", 222), TachiSource("Example", 111)),
    )

    @Test fun aBackupIsRecognisedByItsGzipHeader() {
        assertTrue(MihonBackupImport.looksLikeOne(packed(backup)))
        assertFalse(MihonBackupImport.looksLikeOne("{\"version\":1}".toByteArray()))
        assertFailsWith<IllegalArgumentException> { MihonBackupImport.read(packed(backup).copyOf(30), emptyList()) }
    }

    @Test fun installedExtensionsGetTheirOwnIdsAndTheRestStayUnlinked() {
        val installed = MangaSourceConfig("mihon:org.example:111", "mihon", "Example", "en")
        val result = MihonBackupImport.read(packed(backup), listOf(installed))
        assertEquals(2, result.books.size, "series outside the library are left out")
        assertEquals(1, result.linked); assertEquals(1, result.unlinked)
        val one = result.books.first { it.title == "One" }
        assertEquals("mihon:org.example:111:series:${MihonIds.key("/manga/one")}", one.id)
        assertEquals("mihon:org.example:111:chapter:${MihonIds.key("/c/1")}", one.chapters.first().id)
        assertEquals(listOf("A", "B"), one.authors)
        assertNull(one.unlinkedSourceName())
        val two = result.books.first { it.title == "Two" }
        assertEquals("mihon-backup:222", two.sourceId)
        assertEquals("MangaDex", two.unlinkedSourceName())
        assertTrue(one.chapters.first().id in result.read && two.chapters.single().id in result.read)
        assertFalse(one.chapters[1].id in result.read)
        val place = result.progress.getValue(one.id)
        assertEquals(one.chapters[1].id, place.chapterId, "the newest history entry is the place")
        assertEquals(6, place.page); assertFalse(place.finished)
        assertEquals(setOf(one.id), result.collections.getValue("Reading"))
    }

    @Test fun mergingKeepsWhatTheLibraryHasAndJoinsCollectionsByName() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val result = MihonBackupImport.read(packed(backup), emptyList())
        val existing = result.books.first().copy(title = "Kept")
        store.save(existing)
        store.createCollection("reading")
        result.merge(store)
        val state = store.state.value
        assertEquals(2, state.books.size)
        assertEquals("Kept", state.books.first { it.id == existing.id }.title)
        assertEquals(1, state.collections.size)
        assertTrue(existing.id in state.collections.single().bookIds)
        assertTrue(result.read.all { it in state.readChapters })
    }
}
