package com.cosmotune.manga.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaImageFetchTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun aPostImageKeepsItsBodyAndSourceHeadersWithoutPuttingThemInSavedPageMetadata() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image bytes"))
            val page = MangaPage("page", server.url("/page").toString(), requestMethod = "POST")
            val source = object : MangaImageTransport {
                override val imageClient = OkHttpClient()
                override fun imageRequest(page: MangaPage) = Request.Builder().url(requireNotNull(page.url))
                    .header("X-Source-Token", "private-value").post("page=1&token=body-secret".toRequestBody()).build()
            }
            val output = File(temporary.newFolder(), "page")
            MangaImageFetch.fetch(source.imageClient, source.imageRequest(page), output)
            val received = server.takeRequest()
            assertEquals("POST", received.method); assertEquals("page=1&token=body-secret", received.body.readUtf8())
            assertEquals("private-value", received.getHeader("X-Source-Token")); assertEquals("image bytes", output.readText())
            val encoded = kotlinx.serialization.json.Json.encodeToString(MangaPage.serializer(), page)
            assertFalse(encoded.contains("body-secret")); assertFalse(encoded.contains("private-value"))
            assertFailsWith<IllegalArgumentException> { object : MangaImageTransport { override val imageClient = OkHttpClient() }.imageRequest(page) }
        }
    }
    @Test fun cancellingBlockedImageReadsJoinsTheWriterBeforeItsFileCanBeReused() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old bytes").setBodyDelay(5, TimeUnit.SECONDS))
            val output = File(temporary.newFolder(), "page")
            val request = Request.Builder().url(server.url("/page")).build()
            val task = launch { MangaImageFetch.fetch(OkHttpClient(), request, output) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2_000) { task.cancelAndJoin() }
            server.enqueue(MockResponse().setBody("fresh image"))
            MangaImageFetch.fetch(OkHttpClient(), request, output)
            assertEquals("fresh image", output.readText())
        }
    }
    @Test fun oversizedRequestBodiesAreRejectedBeforeAnyNetworkRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val request = Request.Builder().url(server.url("/page")).post(ByteArray(256 * 1024 + 1).toRequestBody()).build()
            assertFailsWith<IllegalArgumentException> { MangaImageFetch.fetch(OkHttpClient(), request, File(temporary.newFolder(), "page")) }
            assertEquals(0, server.requestCount)
        }
    }
}
