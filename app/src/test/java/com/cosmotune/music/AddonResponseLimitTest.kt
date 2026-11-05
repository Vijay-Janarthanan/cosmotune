package com.cosmotune.music

import com.cosmotune.music.data.sources.MAX_ADDON_JSON_BYTES
import com.cosmotune.music.data.sources.addon.AddonClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LK-01 on the phone client: add-on metadata (manifest, search and catalogue pages) is read within the
 * 2 MiB budget whether the server declares a length or streams chunks, over a real HTTP server.
 */
class AddonResponseLimitTest {
    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, () -> MockResponse>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.requestUrl?.encodedPath.orEmpty()]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    /** A JSON object padded with spaces to exactly [size] bytes. */
    private fun padded(json: String, size: Long): String = json.dropLast(1) + " ".repeat((size - json.length).toInt()) + "}"

    private fun chunked(text: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setChunkedBody(Buffer().writeUtf8(text), 64 * 1024)

    private val manifest = """{"id":"limit","resources":["search","stream","album"]}"""

    @Test fun oversizedManifestFailsWhetherDeclaredOrChunked() = runBlocking<Unit> {
        for (chunkedBody in listOf(false, true)) {
            val text = padded(manifest, MAX_ADDON_JSON_BYTES + 1)
            routes["/manifest.json"] = { if (chunkedBody) chunked(text) else MockResponse().setBody(text) }
            val client = AddonClient(server.url("/").toString())
            try {
                val result = client.manifest()
                assertTrue("chunked=$chunkedBody", result.isFailure)
                assertEquals("Addon response exceeds 2 MB", result.exceptionOrNull()?.message)
            } finally { client.clear() }
        }
    }

    @Test fun chunkedMetadataOfExactlyTheLimitIsAccepted() = runBlocking<Unit> {
        routes["/manifest.json"] = { chunked(padded(manifest, MAX_ADDON_JSON_BYTES)) }
        val client = AddonClient(server.url("/").toString())
        try {
            assertEquals("limit", client.manifest().getOrThrow().id)
        } finally { client.clear() }
    }

    @Test fun oversizedChunkedSearchAndAlbumPagesFailWithoutTakingTheManifestDown() = runBlocking<Unit> {
        routes["/manifest.json"] = { MockResponse().setBody(manifest) }
        routes["/search"] = { chunked(padded("""{"tracks":[]}""", MAX_ADDON_JSON_BYTES + 1)) }
        routes["/album/big"] = { chunked(padded("""{"id":"big","tracks":[]}""", MAX_ADDON_JSON_BYTES + 1)) }
        routes["/album/small"] = { chunked("""{"id":"small","title":"Fits","tracks":[]}""") }
        val client = AddonClient(server.url("/").toString())
        try {
            assertTrue(client.search("anything", "high").isFailure)
            assertEquals("Addon response exceeds 2 MB", client.album("big").exceptionOrNull()?.message)
            assertTrue(client.album("small").isSuccess)
            assertTrue(client.manifest().isSuccess)
        } finally { client.clear() }
    }
}
