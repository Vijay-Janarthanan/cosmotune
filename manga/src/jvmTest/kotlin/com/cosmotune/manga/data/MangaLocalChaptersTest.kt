package com.cosmotune.manga.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** MG-02: chapter folders inside comic archives and image folders, and ComicInfo.xml book metadata. */
class MangaLocalChaptersTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    @Test fun chapterFoldersBecomeOrderedChaptersWithStableIdentities() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val bytes = archive("Series/Ch 10/2.png" to "c10p2", "Series/Ch 2/1.png" to "c2p1", "Series/Ch 10/1.png" to "c10p1",
            "Series/Ch 1/1.png" to "c1p1", "Series/Ch 1/notes.txt" to "ignored")
        val book = store.archives.import("Series.cbz") { ByteArrayInputStream(bytes) }
        assertEquals(listOf("Ch 1", "Ch 2", "Ch 10"), book.chapters.map { it.title })
        assertEquals(3, book.chapters.map { it.id }.toSet().size)
        assertTrue(book.chapters.all { it.bookId == book.id && it.sourceId == "local" && it.id.startsWith("${book.id}:chapter:") })
        val pages = book.chapters.map { chapter -> store.archives.chapterPages(chapter).map { store.archives.pageFile(it).readText() } }
        assertEquals(listOf(listOf("c1p1"), listOf("c2p1"), listOf("c10p1", "c10p2")), pages)
        assertEquals(book.chapters[1], book.nextChapter(book.chapters[0]))
        assertEquals(book.chapters[2], book.nextChapter(book.chapters[1]))
        assertNull(book.nextChapter(book.chapters[2]))
        // The same bytes under another name keep every chapter identity, so progress and read state stay attached.
        assertEquals(book.chapters.map { it.id }, store.archives.import("Renamed.cbz") { ByteArrayInputStream(bytes) }.chapters.map { it.id })
        store.save(book)
        store.progress(MangaProgress(book.id, book.chapters[2].id, 1, 2))
        val reopened = MangaStore(store.root)
        assertEquals(book.chapters[2].id, reopened.state.value.progress.getValue(book.id).chapterId)
        assertTrue(book.chapters[2].id in reopened.state.value.readChapters)
        assertFalse(book.chapters[0].id in reopened.state.value.readChapters)
    }

    @Test fun differingPathPartsNameNestedChaptersAndRootPagesKeepOneChapter() = runBlocking {
        val reader = MangaArchive(temporary.newFolder())
        val nested = reader.import("Volumes.cbz") { ByteArrayInputStream(archive("Vol 2/Ch 1/1.png" to "a", "Vol 1/Ch 1/1.png" to "b")) }
        assertEquals(listOf("Vol 1 · Ch 1", "Vol 2 · Ch 1"), nested.chapters.map { it.title })
        // Pages at the root, or a single folder, stay one chapter with the identity every earlier import used.
        val mixed = reader.import("Mixed.cbz") { ByteArrayInputStream(archive("cover.png" to "c", "Ch 1/1.png" to "1", "Ch 2/1.png" to "2")) }
        assertEquals("${mixed.id}:chapter", mixed.chapters.single().id)
        assertEquals("", mixed.chapters.single().url)
        assertEquals(3, reader.chapterPages(mixed.chapters.single()).size)
        val single = reader.import("Single.cbz") { ByteArrayInputStream(archive("pages/2.png" to "2", "pages/1.png" to "1")) }
        assertEquals("Read", single.chapters.single().title)
        assertEquals(listOf("1", "2"), reader.chapterPages(single.chapters.single()).map { reader.pageFile(it).readText() })
    }

    @Test fun comicInfoDescribesTheBookWithoutResolvingEntities() = runBlocking {
        val reader = MangaArchive(temporary.newFolder())
        val info = """<?xml version="1.0"?>
            <!DOCTYPE ComicInfo [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <ComicInfo><Series>Evening &amp; Morning</Series><Title>The first night</Title><Number>3.5</Number>
            <Summary>Two friends &lt;meet&gt;. &secret;</Summary><Writer>A. Writer, B. Writer</Writer><Penciller>C. Artist</Penciller>
            <Genre>Drama; Slice of life</Genre><LanguageISO>ta</LanguageISO></ComicInfo>"""
        val book = reader.import("file-name.cbz") { ByteArrayInputStream(archive("ComicInfo.xml" to info, "1.png" to "1")) }
        assertEquals("Evening & Morning", book.title)
        assertEquals(listOf("A. Writer", "B. Writer", "C. Artist"), book.authors)
        assertEquals(listOf("Drama", "Slice of life"), book.tags)
        assertEquals("ta", book.language)
        assertTrue(book.description.startsWith("Two friends <meet>."))
        assertFalse("root:" in book.description, "external entities are never resolved")
        val chapter = book.chapters.single()
        assertEquals("The first night", chapter.title)
        assertEquals(3.5f, chapter.number)
        assertEquals(1, reader.chapterPages(chapter).size, "ComicInfo.xml is metadata, not a page")
    }

    @Test fun oversizedOrBrokenComicInfoIsIgnoredAndTheComicStillImports() = runBlocking {
        val reader = MangaArchive(temporary.newFolder())
        val huge = "<ComicInfo><Series>" + "x".repeat(300 * 1024) + "</Series></ComicInfo>"
        assertEquals("Huge", reader.import("Huge.cbz") { ByteArrayInputStream(archive("ComicInfo.xml" to huge, "1.png" to "1")) }.title)
        assertEquals("Broken", reader.import("Broken.cbz") { ByteArrayInputStream(archive("ComicInfo.xml" to "<Other/>", "1.png" to "1")) }.title)
    }

    @Test fun imageFolderChaptersAreCopiedPerChapterWithSafeNames() = runBlocking {
        val reader = MangaArchive(temporary.newFolder())
        val images = listOf("Chapter 10" to "2.png", "Chapter 2" to "1.png", "Chapter 10" to "1.png", "../Chapter 1" to "1.png")
            .map { (chapter, name) -> MangaImageImport(name, chapter) { "$chapter/$name".byteInputStream() } }
        val book = reader.importImages("Series", images)
        assertEquals(listOf("..Chapter 1", "Chapter 2", "Chapter 10"), book.chapters.map { it.title }, "separators are removed from folder names")
        assertEquals(listOf("Chapter 10/1.png", "Chapter 10/2.png"), reader.chapterPages(book.chapters[2]).map { reader.pageFile(it).readText() })
    }
}
