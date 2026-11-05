package com.cosmotune.manga.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearingCacheDuringTransferKeepsPagesAndCancellationReleasesThem() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"number":1},{"number":2}]"""))
            server.enqueue(MockResponse().setBody("first").addHeader("Content-Type", "image/png"))
            val store = MangaStore(temporary.newFolder())
            store.source(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/").toString()))
            val repository = MangaRepository(store)
            val chapter = MangaChapter("chapter", "book", "komga:test", "Chapter", "b1")
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                repository.download(chapter) { _, _ ->
                    runBlocking { repository.clearCache() }
                    assertEquals(1, java.io.File(store.root, "cache/network").listFiles().orEmpty().count { it.extension == "image" })
                    throw kotlinx.coroutines.CancellationException("User cancelled")
                }
            }
            assertTrue(store.state.value.downloads.isEmpty())
            repository.clearCache()
            assertTrue(java.io.File(store.root, "cache/network").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun boundedContextRejectsDecodedChunkedMetadata() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(2 * 1024 * 1024 + 1), 4096).addHeader("Content-Type", "application/json"))
            val context = ParserContext(temporary.newFolder()) { emptyList() }
            assertFailsWith<IOException> {
                context.httpClient.newCall(Request.Builder().url(server.url("/metadata")).build()).execute().use { it.body.string() }
            }
        }
    }

    @Test fun downloadedPagesSurviveRestartAndNeverRetainCredentialHeaders() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"number":1},{"number":2}]"""))
            server.enqueue(MockResponse().setBody("page one").addHeader("Content-Type", "image/png"))
            server.enqueue(MockResponse().setBody("page two").addHeader("Content-Type", "image/png"))
            val store = MangaStore(temporary.newFolder())
            store.source(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/").toString(), settings = mapOf("apiKey" to "test-key")))
            val repository = MangaRepository(store)
            val chapter = MangaChapter("komga:test:book:b1", "komga:test:s1", "komga:test", "Chapter one", "b1")
            repository.download(chapter) { _, _ -> }
            assertEquals(3, server.requestCount)
            val restored = MangaRepository(MangaStore(store.root))
            val pages = restored.pages(chapter)
            assertEquals(listOf("page one", "page two"), pages.map { restored.pageFile(it, chapter.sourceId).readText() })
            assertTrue(pages.all { it.headers.isEmpty() && it.url == null })
            restored.clearCache()
            assertTrue(pages.all { java.io.File(requireNotNull(it.localFile)).isFile })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun aKomgaImageRedirectCannotLeakTheServerKey() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/image")))
            val store = MangaStore(temporary.newFolder())
            store.source(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/").toString(), settings = mapOf("apiKey" to "secret")))
            val repository = MangaRepository(store)
            assertFailsWith<IllegalStateException> {
                repository.pageFile(MangaPage("page", server.url("/page").toString(), headers = mapOf("X-API-Key" to "secret")), "komga:test")
            }
            assertEquals(0, other.requestCount)
        } }
    }
}
