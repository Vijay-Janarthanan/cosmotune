package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class SuwayomiSourceTest {
    @Test fun anExtensionOnTheServerBrowsesListsChaptersAndPages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"fetchSourceManga":{"hasNextPage":true,"mangas":[{"id":7,"title":"Example","thumbnailUrl":"/api/v1/manga/7/thumbnail","author":"A","genre":["Drama"],"status":"ONGOING"}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"fetchChapters":{"chapters":[{"id":2,"name":"Two","chapterNumber":2,"sourceOrder":0,"uploadDate":"5"},{"id":1,"name":"One","chapterNumber":1,"sourceOrder":1}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"manga":{"id":7,"title":"Example","description":"About"}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"fetchChapterPages":{"pages":["/api/v1/manga/7/chapter/1/page/0","/api/v1/manga/7/chapter/1/page/1"]}}}"""))
            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Source not found"}]}"""))
            val config = SuwayomiSource.configuration(server.url("/").toString(), "me", "pw", SuwayomiCatalogue("123", "MangaDex", "en"))
            assertEquals("123", config.settings["source"])
            val source = SuwayomiSource(config, OkHttpClient())
            val page = source.browse("", 0)
            assertEquals(1, page.nextOffset)
            val book = page.books.single()
            assertEquals(server.url("/api/v1/manga/7/thumbnail").toString(), book.coverUrl)
            val first = server.takeRequest()
            assertEquals("/api/graphql", first.path)
            assertTrue(first.getHeader("Authorization")!!.startsWith("Basic "))
            assertTrue(first.body.readUtf8().contains("\"source\":\"123\""))
            val detail = source.details(book)
            assertEquals(listOf("One", "Two"), detail.chapters.map { it.title }, "oldest first")
            assertEquals("About", detail.description)
            val pages = source.pages(detail.chapters.first())
            assertEquals(server.url("/api/v1/manga/7/chapter/1/page/1").toString(), pages[1].url)
            assertTrue(pages.all { it.headers.isEmpty() }, "the sign-in is never kept in page records")
            assertTrue(source.imageRequest(pages[0]).header("Authorization")!!.startsWith("Basic "))
            val failure = assertFailsWith<MangaSourceFailure> { source.browse("x", 0) }
            assertTrue(failure.message!!.contains("Source not found"))
        }
    }

    @Test fun aServerKeepsOneLibrarySourceAndOneSourcePerExtension() {
        val library = SuwayomiSource.configuration("https://home.example:4567/")
        assertEquals(library.id, SuwayomiSource.configuration("https://home.example:4567").id)
        assertTrue(library.id.endsWith(":library"))
        assertNotEquals(library.id, SuwayomiSource.configuration("https://home.example:4567", catalogue = SuwayomiCatalogue("9", "X", "en")).id)
        assertFailsWith<IllegalArgumentException> { SuwayomiSource.configuration("https://user:pw@home.example") }
    }
}
