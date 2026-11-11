package com.cosmotune.watch

import com.cosmotune.watch.debrid.WatchDebridClient
import com.cosmotune.watch.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

class WatchDebridClientTest {
    private val stream = Stream(StreamSource("test", "An add-on", "ADD"), "A copy", url = "magnet:?xt=urn:btih:${"a".repeat(40)}", protocol = StreamProtocol.PROGRESSIVE, filename = "episode-2.mkv")
    @Test fun aPackNeverSubstitutesAnotherEpisodeOrTheLargestFile() {
        val client = WatchDebridClient(OkHttpClient())
        val files = listOf(WatchDebridClient.FileOffer("1", "/episode-1.mkv"), WatchDebridClient.FileOffer("2", "/episode-2.mkv"))
        assertEquals("2", client.choose(files, stream).id)
        assertFailsWith<IllegalArgumentException> { client.choose(files, stream.copy(filename = "episode-3.mkv")) }
        assertFailsWith<IllegalArgumentException> { client.choose(files, stream.copy(filename = null)) }
    }
    @Test fun realDebridUsesOnlyTheChosenFileAndReturnsAnUnrestrictedAddress() = runBlocking {
        TestServer().use { server ->
            server.on("/torrents/addMagnet") { MockResponse().setBody("""{"id":"fixture"}""") }
            server.on("/torrents/info/fixture") { MockResponse().setBody("""{"status":"downloaded","files":[{"id":1,"path":"/episode-1.mkv","selected":0},{"id":2,"path":"/episode-2.mkv","selected":1}],"links":["https://example.com/host-link"]}""") }
            server.on("/torrents/selectFiles/fixture") { request -> assertEquals("files=2", request.body.readUtf8()); MockResponse().setResponseCode(204) }
            server.on("/unrestrict/link") { MockResponse().setBody("""{"download":"https://example.com/episode-2.mkv"}""") }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.REAL_DEBRID to server.base))
            assertEquals("https://example.com/episode-2.mkv", client.resolve(WatchDebrid.REAL_DEBRID, "test-token", stream))
            assertTrue(server.requests.all { it.getHeader("Authorization") == "Bearer test-token" })
        }
    }
}
