package com.cosmotune.manga.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg names: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> names.forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write("image:$name".toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    @Test fun archivePagesUseNaturalOrderAndCannotEscapeTheCache() = runBlocking {
        val root = temporary.newFolder()
        val reader = MangaArchive(root)
        val bytes = archive("pages/10.png", "pages/2.png", "../outside.png", "pages/1.png", "notes.txt")
        val book = reader.import("Comic.cbz") { ByteArrayInputStream(bytes) }
        val pages = reader.chapterPages(book.chapters.single())
        assertEquals(listOf("../outside.png", "pages/1.png", "pages/2.png", "pages/10.png"), pages.map { it.archiveEntry })
        val extracted = reader.pageFile(pages.first())
        assertEquals(File(root, "cache/pages").canonicalFile, extracted.canonicalFile.parentFile)
        assertEquals("image:../outside.png", extracted.readText())
        assertFalse(File(root, "outside.png").exists())
        assertEquals(extracted, reader.pageFile(pages.first()))
    }

    @Test fun contentIdentityDeduplicatesImportsAndProgressSurvivesRestart() = runBlocking {
        val root = temporary.newFolder()
        val store = MangaStore(root)
        val bytes = archive("1.jpg", "2.jpg")
        val first = store.archives.import("First.cbz") { ByteArrayInputStream(bytes) }
        val renamed = store.archives.import("Renamed.cbz") { ByteArrayInputStream(bytes) }
        assertEquals(first.id, renamed.id)
        store.save(first); store.save(renamed)
        store.progress(MangaProgress(first.id, first.chapters.single().id, 1, 2, MangaReaderMode.WEBTOON, reachedEnd = true))
        val restored = MangaStore(root).state.value
        assertEquals(1, restored.books.size)
        assertEquals("Renamed", restored.books.single().title)
        assertTrue(restored.progress.getValue(first.id).finished)
        assertEquals(MangaReaderMode.WEBTOON, restored.readerMode)
        assertEquals(2, MangaStore(root).archives.chapterPages(restored.books.single().chapters.single()).size)
    }

    @Test fun foreignArchivePathsAndInvalidProgressAreRejected() = runBlocking {
        val root = temporary.newFolder()
        val outside = temporary.newFile("outside.cbz").apply { writeBytes(archive("1.jpg")) }
        val store = MangaStore(root)
        val chapter = MangaChapter("x", "b", "local", "Read", "", archiveFile = outside.path)
        assertFailsWith<IllegalArgumentException> { store.archives.chapterPages(chapter) }
        assertFailsWith<IllegalArgumentException> { store.progress(MangaProgress("b", "x", -1, 2)) }
        assertFailsWith<IllegalArgumentException> { store.progress(MangaProgress("b", "x", 2, 2)) }
        assertTrue(store.state.value.progress.isEmpty())
    }

    @Test fun emptyAndMalformedArchivesLeaveNoPartialImports() = runBlocking {
        val root = temporary.newFolder()
        val reader = MangaArchive(root)
        assertFailsWith<IllegalArgumentException> { reader.import("empty.cbz") { ByteArrayInputStream(archive("readme.txt")) } }
        assertFails { reader.import("broken.cbz") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) } }
        assertTrue(File(root, "books").listFiles().orEmpty().isEmpty())
    }

    @Test fun corruptedLibraryIsPreservedBeforeRecovery() = runBlocking {
        val root = temporary.newFolder()
        File(root, "library.json").writeText("{ interrupted")
        val store = MangaStore(root)
        assertTrue(store.state.value.books.isEmpty())
        val preserved = root.listFiles().orEmpty().single { it.name.startsWith("library-unreadable-") }
        assertEquals("{ interrupted", preserved.readText())
        store.save(MangaBook("test:1", "test", "Recovered"))
        assertEquals("Recovered", MangaStore(root).state.value.books.single().title)
        assertEquals("{ interrupted", preserved.readText())
    }

    @Test fun reopeningDuringPageWritesAlwaysReadsACompleteLibrary() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = MangaStore(root)
        store.save(MangaBook("book", "local", "Concurrent reader"))
        store.progress(MangaProgress("book", "chapter", 0, 20))
        coroutineScope {
            launch(Dispatchers.IO) { repeat(60) { store.progress(MangaProgress("book", "chapter", it % 20, 20)) } }
            launch(Dispatchers.IO) { repeat(60) {
                val reopened = MangaStore(root)
                assertNull(reopened.recoveryMessage)
                assertEquals(1, reopened.state.value.books.size)
                assertNotNull(reopened.state.value.progress["book"])
            } }
        }
    }

    @Test fun imageFolderIsCopiedInPageOrderWithoutKeepingExternalPaths() = runBlocking {
        val reader = MangaArchive(temporary.newFolder())
        val book = reader.importImages("Folder", listOf("10.png", "2.png", "1.png").map { name -> MangaImageImport(name) { name.byteInputStream() } })
        val pages = reader.chapterPages(book.chapters.single())
        assertEquals(listOf("1.png", "2.png", "10.png"), pages.map { reader.pageFile(it).readText() })
    }
}
