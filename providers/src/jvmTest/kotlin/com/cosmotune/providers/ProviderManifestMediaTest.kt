package com.cosmotune.providers

import java.io.File
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderManifestMediaTest {
    @Test fun cancellingTheTransportInterruptsAnActiveSegmentAndRemovesItsPartialFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\nsegment.aac\n#EXT-X-ENDLIST"))
            server.enqueue(MockResponse().setBody("a".repeat(10_000)).throttleBody(32, 1, java.util.concurrent.TimeUnit.SECONDS))
            val transport = network(); val base = target()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val work = executor.submit<Boolean> { runCatching { ProviderManifestMedia(transport, 20_000).download(server.url("/list").toString(), base, JsonObject(emptyMap()), false) }.isFailure }
                server.takeRequest(); server.takeRequest(); transport.cancel()
                assertTrue(work.get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(base.parentFile.listFiles().orEmpty().isEmpty())
            } finally { transport.cancel(); executor.shutdownNow() }
        }
    }
    @get:Rule val temporary = TemporaryFolder()
    private fun network() = ProviderNetwork(ProviderPermissions(listOf("localhost"), allowHttp = true), true, OkHttpClient())
    private fun target() = File(temporary.newFolder(), "audio")
    @Test fun finiteHlsAudioRenditionIsFetchedThroughTheScopedTransportAndJoinedInOrder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/list.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO=\"a\"\nvideo.m3u8"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:0\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\n1.m4s\n#EXTINF:2,\n2.m4s\n#EXT-X-ENDLIST"))
            listOf("init", "first", "second").forEach { server.enqueue(MockResponse().setBody(it)) }
            val file = ProviderManifestMedia(network(), 100).download(server.url("/master.m3u8").toString(), target(), buildJsonObject { put("Authorization", "fixture") }, false)
            assertEquals("initfirstsecond", file.readText()); assertEquals("mp4", file.extension)
            val requests = (0..4).map { server.takeRequest() }
            assertEquals(listOf("/master.m3u8", "/audio/list.m3u8", "/audio/init.mp4", "/audio/1.m4s", "/audio/2.m4s"), requests.map { it.path })
            assertTrue(requests.all { it.getHeader("Authorization") == "fixture" })
        }
    }
    @Test fun staticDashAudioTemplateIgnoresVideoAndExpandsBoundedNumberTimeline() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<MPD type="static"><Period><AdaptationSet mimeType="video/mp4"><Representation id="video"/></AdaptationSet><AdaptationSet contentType="audio"><BaseURL>audio/</BaseURL><SegmentTemplate initialization="${'$'}RepresentationID${'$'}-init.mp4" media="${'$'}RepresentationID${'$'}-${'$'}Number%03d${'$'}.m4s" startNumber="5"><SegmentTimeline><S d="2000" r="1"/></SegmentTimeline></SegmentTemplate><Representation id="sound" bandwidth="128000"/></AdaptationSet></Period></MPD>"""))
            listOf("init", "one", "two").forEach { server.enqueue(MockResponse().setBody(it)) }
            val file = ProviderManifestMedia(network(), 100).download(server.url("/manifest.mpd").toString(), target(), JsonObject(emptyMap()), false)
            assertEquals("initonetwo", file.readText())
            assertEquals(listOf("/manifest.mpd", "/audio/sound-init.mp4", "/audio/sound-005.m4s", "/audio/sound-006.m4s"), (0..3).map { server.takeRequest().path })
        }
    }
    @Test fun childOriginsDoNotInheritExplicitCredentialsAndUndeclaredHostsNeverFetch() {
        MockWebServer().use { root -> MockWebServer().use { child ->
            root.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:2,\n${child.url("/audio.aac")}\n#EXT-X-ENDLIST"))
            child.enqueue(MockResponse().setBody("audio"))
            ProviderManifestMedia(network(), 100).download(root.url("/list").toString(), target(), buildJsonObject { put("Cookie", "fixture=private"); put("Authorization", "fixture") }, false)
            assertNull(child.takeRequest().getHeader("Authorization")); assertEquals("fixture", root.takeRequest().getHeader("Authorization"))
            root.enqueue(MockResponse().setBody("#EXTM3U\nhttps://unlisted.invalid/audio\n#EXT-X-ENDLIST"))
            assertFails { ProviderManifestMedia(network(), 100).download(root.url("/list").toString(), target(), JsonObject(emptyMap()), false) }
        } }
    }
    @Test fun liveEncryptionExternalEntitiesAndUnboundedTimelinesFailBeforeMediaRequests() {
        val invalid = listOf("#EXTM3U\n#EXTINF:2,\na.aac", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.aac\n#EXT-X-ENDLIST",
            """<!DOCTYPE MPD [<!ENTITY external SYSTEM "file:///secret">]><MPD><Period/></MPD>""",
            """<MPD type="dynamic"><Period/></MPD>""",
            """<MPD><Period><AdaptationSet contentType="audio"><ContentProtection/><Representation id="a"><BaseURL>audio.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>""")
        for (text in invalid) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text))
            assertFails { ProviderManifestMedia(network(), 100).download(server.url("/manifest").toString(), target(), JsonObject(emptyMap()), false) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun incompleteRangesAndQuotasDeleteAllPartialOutput() {
        for (range in listOf(false, true)) MockWebServer().use { server ->
            val manifest = if (range) """<MPD><Period><AdaptationSet contentType="audio"><Representation id="a"><SegmentList><SegmentURL media="audio.mp4" mediaRange="2-5"/></SegmentList></Representation></AdaptationSet></Period></MPD>""" else "#EXTM3U\na.aac\n#EXT-X-ENDLIST"
            server.enqueue(MockResponse().setBody(manifest)); server.enqueue(MockResponse().setBody("too much"))
            val base = target()
            assertFails { ProviderManifestMedia(network(), 3).download(server.url("/list").toString(), base, JsonObject(emptyMap()), false) }
            assertTrue(base.parentFile.listFiles().orEmpty().isEmpty())
        }
    }
}
