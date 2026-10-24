package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class KomgaSourceTest {
    @Test fun authenticatedCatalogueDetailsAndPagesPreserveSourceIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"id":"s1","name":"Series","metadata":{"title":"Example","language":"en"}}],"last":true}"""))
            server.enqueue(MockResponse().setBody("""{"content":[{"id":"b1","metadata":{"title":"Chapter one","numberSort":1}}],"last":true}"""))
            server.enqueue(MockResponse().setBody("""[{"number":1},{"number":2}]"""))
            val config = MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/library/").toString(), settings = mapOf("apiKey" to "test-key"))
            val source = KomgaSource(config, OkHttpClient())
            val result = source.browse("a & b", 0)
            assertNull(result.nextOffset)
            val book = result.books.single()
            assertEquals("komga:test:s1", book.id)
            val first = server.takeRequest()
            assertEquals("test-key", first.getHeader("X-API-Key"))
            assertEquals("a & b", first.requestUrl?.queryParameter("search"))
            assertEquals("/library/api/v1/series", first.requestUrl?.encodedPath)
            val detail = source.details(book)
            assertEquals("komga:test:book:b1", detail.chapters.single().id)
            assertEquals(book.id, detail.chapters.single().bookId)
            val pages = source.pages(detail.chapters.single())
            assertEquals(2, pages.size)
            assertEquals(server.url("/library/api/v1/books/b1/pages/1").toString(), pages.first().url)
            assertEquals("test-key", pages.first().headers["X-API-Key"])
        }
    }

    @Test fun privateApiKeyIsNotForwardedAcrossRedirects() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/capture")))
            val source = KomgaSource(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = origin.url("/").toString(), settings = mapOf("apiKey" to "secret")), OkHttpClient())
            assertFailsWith<IllegalStateException> { source.browse() }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun chunkedOversizedMetadataIsRejectedBeforeJsonDecoding() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(" ".repeat(2 * 1024 * 1024 + 1), 4096))
            val source = KomgaSource(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/").toString()), OkHttpClient())
            assertFailsWith<IllegalArgumentException> { source.browse() }
        }
    }
}
