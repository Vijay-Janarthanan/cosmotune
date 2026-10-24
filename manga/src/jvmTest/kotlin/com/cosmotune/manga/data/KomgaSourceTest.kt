package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class KomgaSourceTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
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
            assertTrue(pages.first().headers.isEmpty(), "the key is never stored in page records")
            assertEquals("test-key", source.imageRequest(pages.first()).header("X-API-Key"), "it is added to the server's own page requests")
        }
    }

    @Test fun passwordSignInMetadataCoversAndRejectedSignInAreHandled() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"id":"s1","name":"Folder name","metadata":{"title":"","summary":"About","genres":["Drama"],"tags":["Ongoing","Drama"]},
                "booksMetadata":{"authors":[{"name":"A. Writer","role":"writer"},{"name":"B. Artist","role":"penciller"},{"name":"A. Writer","role":"cover"}]}}],"last":false}"""))
            val config = KomgaSource.configuration(server.url("/").toString(), username = "reader", password = "pässword")
            assertEquals(config.id, KomgaSource.configuration(server.url("/").toString(), apiKey = "new-key").id, "the same server keeps one identity")
            assertFailsWith<IllegalArgumentException> { KomgaSource.configuration(server.url("/").toString()) }
            val source = KomgaSource(config, OkHttpClient())
            val page = source.browse()
            assertEquals(40, page.nextOffset)
            val book = page.books.single()
            assertEquals("Folder name", book.title)
            assertEquals(listOf("A. Writer", "B. Artist"), book.authors)
            assertEquals(listOf("Drama", "Ongoing"), book.tags)
            assertEquals("About", book.description)
            assertEquals(server.url("/api/v1/series/s1/thumbnail").toString(), book.coverUrl)
            assertEquals(okhttp3.Credentials.basic("reader", "pässword", Charsets.UTF_8), server.takeRequest().getHeader("Authorization"))
            assertFailsWith<IllegalArgumentException> { source.imageRequest(MangaPage("x", "https://elsewhere.invalid/api/v1/books/b/pages/1")) }
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue("rejected the API key or sign-in" in assertFailsWith<MangaSourceFailure> { source.browse() }.message.orEmpty())
        }
    }

    @Test fun signedCoversAndPagesAreFetchedThroughTheRepositoryWithoutFollowingRedirects() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val store = MangaStore(temporary.newFolder())
            val config = KomgaSource.configuration(server.url("/").toString(), apiKey = "secret")
            store.source(config)
            val repository = MangaRepository(store)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(7, 7, 7))))
            val cover = repository.pageFile(MangaPage("komga:cover", server.url("/api/v1/series/s1/thumbnail").toString()), config.id)
            assertContentEquals(byteArrayOf(7, 7, 7), cover.readBytes())
            assertEquals("secret", server.takeRequest().getHeader("X-API-Key"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/collect")))
            assertFails { repository.pageFile(MangaPage("komga:moved", server.url("/api/v1/books/b/pages/1").toString()), config.id) }
            assertEquals(0, other.requestCount, "a redirect never carries the key to another server")
        } }
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
