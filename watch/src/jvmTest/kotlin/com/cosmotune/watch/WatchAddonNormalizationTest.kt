package com.cosmotune.watch

import com.cosmotune.watch.model.*
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugins.SourceAddress
import com.cosmotune.watch.plugins.stremio.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class WatchAddonNormalizationTest {
    @Test fun normalizesMalformedNetworkSlashesAndInlineHeadersWithoutHeaderInjection() {
        val parsed = assertNotNull(SourceAddress.parse("https:/cdn.example/a file.mp4|User-Agent=Player%2F1&Referer=https%3A%2F%2Forigin.example&X-Bad=bad%0D%0AInjected%3Ayes&Host=evil.example", mapOf("user-agent" to "Explicit/2")))
        assertEquals("https://cdn.example/a%20file.mp4", parsed.url)
        assertEquals(mapOf("user-agent" to "Explicit/2", "Referer" to "https://origin.example"), parsed.headers)
        assertNull(SourceAddress.parse("file:///private/video.mp4"))
        assertNull(SourceAddress.parse("javascript:alert(1)"))
        assertNull(SourceAddress.parse("https://user:password@cdn.example/file.mp4"))
        assertNull(SourceAddress.parse("https://cdn.example/file.mp4\n"))
        assertEquals("https://cdn.example/a.mp4", SourceAddress.parse("//cdn.example/a.mp4")?.url)
    }

    @Test fun manifestNormalizationKeepsConfigurationAndResourceRestrictionsApplyToEveryEntry() = runBlocking {
        assertEquals("https://addon.example:8443/config/manifest.json?token=opaque", StremioClient.normalise("addon.example:8443/config?token=opaque"))
        assertEquals("https://addon.example/manifest.json", StremioClient.normalise("https:/addon.example"))
        assertFailsWith<IllegalArgumentException> { StremioClient.normalise("ftp://addon.example") }
        assertFailsWith<IllegalArgumentException> { StremioClient.normalise("https://addon.example|Authorization=secret") }
        TestServer().use { server ->
            server.json("/manifest.json", """{"id":"fixture","name":"Fixture","types":["movie","series"],"resources":[{"name":"stream","types":["movie"],"idPrefixes":["tt"]},{"name":"stream","types":["series"],"idPrefixes":["own:"]}]}""")
            server.json("/stream/series/own:series:1:2.json", """{"streams":[{"url":"https:/cdn.example/episode.mp4|Referer=https%3A%2F%2Forigin.example","name":"720p"}]}""")
            val client = StremioClient(server.http(), server.url("/manifest.json?token=opaque"))
            val manifest = client.manifest()
            assertTrue(manifest.supports("stream", "series", "own:series:1:2"))
            assertFalse(manifest.supports("stream", "series", "tt123:1:2"))
            client.streams("series", "own:series:1:2")
            assertTrue(server.paths().all { it.endsWith("?token=opaque") })
        }
    }

    @Test fun episodeVideosInferSeriesAndNativeEpisodeIdsMatchTheRequestedEpisode() = runBlocking {
        TestServer().use { server ->
            server.json("/manifest.json", """{"id":"fixture","name":"Fixture","types":["series"],"resources":["meta","stream"]}""")
            server.json("/meta/series/own:series.json", """{"meta":{"id":"own:series","name":"Fixture series","type":"movie","videos":[{"id":"native:first","season":1,"episode":1,"title":"One"},{"id":"native:second","season":1,"episode":2,"title":"Two"},{"id":"own:series:2:1","title":"Next season"}]}}""")
            server.json("/stream/series/native:second.json", """{"streams":[{"name":"720p","url":"https:/cdn.example/second.mp4|User-Agent=Fixture"}]}""")
            val plugin = StremioPlugin("fixture", "Fixture", "TEST", StremioClient(server.http(), server.url("/manifest.json")), setOf(Capability.STREAMS))
            val key = WatchKey.series("fixture", "own:series")
            val title = assertNotNull(plugin.title(key))
            assertEquals(TitleType.SERIES, title.type)
            assertEquals(listOf(1, 2), title.seasons.map { it.number })
            assertEquals(listOf(1, 2), plugin.episodes(key, 1).map { it.number })
            val target = PlayTarget.EpisodeOf(WatchKey.episode("fixture", "own:series", 1, 2), ExternalIds(), "Fixture", 1, 2)
            val stream = plugin.streams(target).single()
            assertEquals("https://cdn.example/second.mp4", stream.url)
            assertEquals("Fixture", stream.headers["User-Agent"])
            assertTrue(server.paths().none { it.contains("native:first.json") })
            assertNull(plugin.summaryOf(Json.parseToJsonElement("""{"id":"trailer","name":"Film","videos":[{"id":"youtube","title":"Trailer"}]}""")), "trailer-only videos do not invent a series")
        }
    }
}
