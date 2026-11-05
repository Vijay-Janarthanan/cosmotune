package com.cosmotune.reading.data

import com.cosmotune.manga.data.MangaFileProtection
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun epub(extra: Map<String, String> = emptyMap()): ByteArray = ByteArrayOutputStream().use { output ->
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""",
            "OEBPS/package.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>A public-domain story</dc:title><dc:creator>Test author</dc:creator><dc:language>en</dc:language></metadata>
              <manifest><item id="text" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              <item id="cover" href="colour.svg" media-type="image/svg+xml" properties="cover-image"/>
              <item id="css" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="text"/></spine></package>""",
            "OEBPS/text/chapter.xhtml" to """<!DOCTYPE html><html><head><link rel="stylesheet" href="../style.css"/></head><body><h1 id="start">A story</h1><img src="../colour.svg"/><p>Original fixture text, released to the public domain.</p></body></html>""",
            "OEBPS/nav.xhtml" to """<!DOCTYPE html><html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="text/chapter.xhtml#start">Chapter one</a></li></ol></nav></body></html>""",
            "OEBPS/colour.svg" to """<svg xmlns="http://www.w3.org/2000/svg"><rect width="20" height="20" fill="#e83422"/></svg>""",
            "OEBPS/style.css" to "body { color: #162830; } img { width: 100%; }"
        ) + extra
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        output.toByteArray()
    }

    @Test fun colourEpubOwnsValidatedResourcesAndRestoresEditionBookmarksPreferencesAndCollections() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val repository = ReadingRepository(root)
        val bytes = epub()
        val book = repository.importFile("Story.epub") { bytes.inputStream() }
        assertTrue(book.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals("A public-domain story", book.title)
        assertEquals(listOf("Test author"), book.authors)
        val document = assertIs<ReadingDocument.Epub>(repository.openDocument(book.id))
        assertEquals("OEBPS/text/chapter.xhtml", document.spine.single().href)
        assertEquals("OEBPS/text/chapter.xhtml#start", document.navigation.single().href)
        assertTrue(document.resources.getValue("OEBPS/colour.svg").file.readText().contains("#e83422"))
        assertTrue(document.resources.getValue("OEBPS/style.css").file.readText().contains("#162830"))
        val locator = ReadingLocator(document.spine.single().href, .42, scrollOffset = 130.0)
        repository.saveLocator(book.id, locator)
        val bookmark = repository.saveBookmark(book.id, "A colourful page", locator)
        repository.updatePreferences(ReadingPreferences(fontScale = 1.8f, marginDp = 30))
        val collection = repository.createCollection("Evening books")
        repository.assignCollection(book.id, collection.id, true)
        repository.markFinished(book.id)
        val duplicate = repository.importFile("Renamed copy.epub") { bytes.inputStream() }
        assertEquals(book.id, duplicate.id)
        val restored = ReadingRepository(root)
        assertEquals(1, restored.state.value.books.size)
        assertEquals(locator, restored.state.value.progress.getValue(book.id).locator)
        assertEquals(bookmark, restored.state.value.bookmarks.single())
        assertEquals(1.8f, restored.state.value.preferences.fontScale)
        assertTrue(book.id in restored.state.value.collections.single().bookIds)
        assertTrue(book.id in restored.state.value.finished)
        File(book.filePath).appendText("changed")
        assertFailsWith<IllegalArgumentException> { restored.openDocument(book.id) }
    }

    @Test fun malformedTraversalProtectedAndOversizedImportsCannotEscapeOwnedStorage() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        assertFailsWith<IllegalArgumentException> { repository.importFile("bad.epub") { epub(mapOf("../escaped.txt" to "bad")).inputStream() } }
        assertFailsWith<IllegalArgumentException> { repository.importFile("bad.epub") { epub(mapOf("META-INF/encryption.xml" to "<encryption/>" )).inputStream() } }
        assertFailsWith<IllegalArgumentException> { repository.importFile("html.pdf") { "<html>login</html>".byteInputStream() } }
        assertTrue(repository.state.value.books.isEmpty())
        assertFalse(File(repository.root.parentFile, "escaped.txt").exists())
        assertTrue(File(repository.root, "books").listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test fun pdfImportIsOwnedAndEncryptedPdfIsRejectedWithoutAlteringOriginalFiles() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        val minimal = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF".toByteArray()
        val source = temporary.newFile("original.pdf").apply { writeBytes(minimal) }
        val book = repository.importFile(source.name) { source.inputStream() }
        assertIs<ReadingDocument.Pdf>(repository.openDocument(book.id))
        assertFailsWith<IllegalArgumentException> { repository.importFile("protected.pdf") { "%PDF-1.7\ntrailer << /Encrypt 8 0 R >>\n%%EOF".byteInputStream() } }
        repository.removeBook(book.id)
        assertTrue(source.exists())
        assertFalse(File(book.filePath).exists())
    }

    @Test fun credentialProtectionAndAtomicFailurePreserveThePreviousLibrary() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val protection = object : MangaFileProtection {
            var fail = false
            override fun seal(bytes: ByteArray): ByteArray { check(!fail); return bytes.reversedArray() }
            override fun open(bytes: ByteArray) = bytes.reversedArray()
        }
        val repository = ReadingRepository(root, protection)
        repository.saveOpdsSource("My books", "https://books.example/opds", "reader", "private-secret")
        val previous = File(root, "reading.json").readBytes()
        assertFalse(previous.toString(Charsets.UTF_8).contains("private-secret"))
        protection.fail = true
        assertFailsWith<IllegalStateException> { repository.createCollection("Unwritten") }
        assertContentEquals(previous, File(root, "reading.json").readBytes())
        assertEquals("private-secret", ReadingRepository(root, protection).state.value.sources.single().password)
    }

    @Test fun acquisitionFollowsOnlySameOriginAndRejectsSamplesLoginPagesAndCancelledTransfers() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val repository = ReadingRepository(temporary.newFolder())
            val source = repository.saveOpdsSource("Private", server.url("/opds").toString(), "reader", "private", true)
            val link = ReadingLink("EPUB", server.url("/download").toString(), "application/epub+zip", kind = ReadingLinkKind.ACQUIRE)
            val publication = ReadingPublication("one", "Owned story", links = listOf(link), sourceId = source.id)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(epub())).setHeader("Content-Type", "application/epub+zip"))
            assertEquals(ReadingFormat.EPUB, repository.download(publication, link).format)
            assertEquals(Credentials.basic("reader", "private"), server.takeRequest().getHeader("Authorization"))
            assertEquals(Credentials.basic("reader", "private"), server.takeRequest().getHeader("Authorization"))
            assertFailsWith<IllegalArgumentException> { repository.download(publication, link.copy(kind = ReadingLinkKind.SAMPLE)) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
            assertFailsWith<IllegalArgumentException> { repository.download(publication, link) }
            assertEquals(0, other.requestCount)
            server.enqueue(MockResponse().setBody("<html>sign in</html>").setHeader("Content-Type", "text/html"))
            assertFailsWith<IllegalArgumentException> { repository.download(publication, link) }
            server.enqueue(MockResponse().setBody("x".repeat(512_000)).setHeader("Content-Type", "application/epub+zip").throttleBody(1, 5, TimeUnit.SECONDS))
            val transfer = async { repository.download(publication, link) }
            while (server.requestCount < 5) delay(10)
            withTimeout(2_000) { transfer.cancelAndJoin() }
            assertTrue(File(repository.root, "books").listFiles().orEmpty().none { it.name.startsWith("download-") })
        } }
    }
}
