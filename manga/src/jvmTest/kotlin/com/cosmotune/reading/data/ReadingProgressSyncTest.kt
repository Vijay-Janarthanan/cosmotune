package com.cosmotune.reading.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingProgressSyncTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun koreaderIdentitySamplesTheFileLikeKoreader() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val file = File(temporary.root, "book.epub").apply { writeBytes(bytes) }
        val md5 = MessageDigest.getInstance("MD5")
        listOf(0, 1024, 4096, 16384).forEach { at -> md5.update(bytes, at, minOf(1024, bytes.size - at)) }
        assertEquals(md5.digest().joinToString("") { "%02x".format(it) }, ReadingKoSyncClient.documentHash(file))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", ReadingKoSyncClient.key("password"))
    }

    @Test fun positionsTravelAsKoreaderWritesThem() {
        val spine = listOf("OEBPS/a.xhtml", "OEBPS/b.xhtml", "OEBPS/c.xhtml")
        assertEquals("/body/DocFragment[2]/body", ReadingKoSyncClient.progressOf(ReadingFormat.EPUB, ReadingLocator("OEBPS/b.xhtml#x", .4), spine))
        assertEquals("OEBPS/c.xhtml", ReadingKoSyncClient.locatorOf(ReadingFormat.EPUB, "/body/DocFragment[3]/body/div/p[5]/text().12", spine)?.resourceHref)
        assertEquals("7", ReadingKoSyncClient.progressOf(ReadingFormat.PDF, ReadingLocator(page = 6), spine))
        assertEquals(6, ReadingKoSyncClient.locatorOf(ReadingFormat.PDF, "7", spine)?.page)
        assertNull(ReadingKoSyncClient.locatorOf(ReadingFormat.EPUB, "/body/DocFragment[9]/body", spine))
    }

    @Test fun libraryServersAreKnownByTheirDownloadAddress() {
        val komga = assertIs<ReadingServerProgress.Komga>(ReadingServerProgress.target("https://books.example/komga/opds/v1.2/books/0ABC12/file"))
        assertEquals("https://books.example/komga/", komga.base.toString())
        assertEquals("0ABC12", komga.book)
        val kavita = assertIs<ReadingServerProgress.Kavita>(ReadingServerProgress.target("https://k.example/api/opds/key-1/series/4/volume/9/chapter/12/download/x.epub"))
        assertEquals(listOf(4, 9, 12), listOf(kavita.series, kavita.volume, kavita.chapter))
        assertEquals("key-1", kavita.apiKey)
        assertNull(ReadingServerProgress.target("https://www.gutenberg.org/ebooks/1.epub"))
    }

    @Test fun signsInSendsAndFetchesPlacesOnAKosyncServer() = runBlocking<Unit> {
        val server = MockWebServer()
        val stored = HashMap<String, String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/users/create") assertEquals("reader", request.getHeader("x-auth-user"))
                return when {
                    request.path == "/users/create" -> MockResponse().setResponseCode(201).setBody("{\"username\":\"reader\"}")
                    request.path == "/users/auth" -> if (request.getHeader("x-auth-key") == ReadingKoSyncClient.key("pw")) MockResponse().setBody("{\"authorized\":\"OK\"}") else MockResponse().setResponseCode(401)
                    request.method == "PUT" -> { stored["doc"] = request.body.readUtf8(); MockResponse().setBody("{}") }
                    request.path!!.startsWith("/syncs/progress/") -> MockResponse().setBody("""{"document":"d","progress":"/body/DocFragment[2]/body","percentage":0.5,"device":"Kobo","device_id":"OTHER","timestamp":${System.currentTimeMillis() / 1000 + 60}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val repository = ReadingRepository(temporary.newFolder())
        val book = repository.importFile("Tale.txt") { "Chapter 1\n\nOne.\n\nChapter 2\n\nTwo.\n".byteInputStream() }
        assertFailsWith<IllegalStateException> { repository.connectKoSync(server.url("/").toString(), "reader", "wrong", create = false) }
        repository.connectKoSync(server.url("/").toString(), "reader", "pw", create = true)
        val spine = listOf("OEBPS/c0001.xhtml", "OEBPS/c0002.xhtml")
        repository.saveLocator(book.id, ReadingLocator("OEBPS/c0001.xhtml", .2))
        val remote = assertNotNull(repository.remotePosition(book.id, spine))
        assertEquals("OEBPS/c0002.xhtml", remote.locator.resourceHref)
        assertEquals("Kobo", remote.device)
        server.shutdown()
    }
}
