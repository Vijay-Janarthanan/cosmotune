package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugins.free.FreeLiveLists
import com.cosmotune.watch.plugins.free.FreeServices
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreeServicesTest {
    private fun gz(text: String) = Buffer().write(ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(text.toByteArray()) } }.toByteArray())

    @Test fun samsungListsOnlyTheDevicesRegionWithItsGuide() = runBlocking<Unit> {
        TestServer().use { s ->
            s.on("/SamsungTVPlus/.channels.json.gz") { MockResponse().setBody(gz("""{"slug":"stvp-{id}","headers":{"user-agent":"okhttp/4.12.0"},"regions":{
                "in":{"name":"India","channels":{"IN1":{"name":"The Movie Club","chno":1001,"logo":"https://l/1.png","group":"Movies","programs":[[1793960000,"Film A"],[1793967200,"Film B"]]}}},
                "us":{"name":"USA","channels":{"US1":{"name":"US Only","chno":1,"group":"News","programs":[]}}}}}""")) }
            val plugin = FreeServices.samsung(s.http(), FreeLiveLists(s.base) { listOf("in") })
            val channels = plugin.channels()
            assertEquals(listOf("The Movie Club"), channels.map { it.name }, "no other region is listed")
            assertEquals(WatchKey("c:samsung:IN1"), channels[0].key)
            assertEquals(listOf("Movies"), channels[0].categories)
            val guide = plugin.guide(channels, 1793960000_000, 1793970000_000)
            assertEquals(listOf("Film A", "Film B"), guide.map { it.title })
            assertEquals(1793967200_000, guide[0].stopMs)
            val stream = plugin.streams(PlayTarget.Live(channels[0])).single()
            assertEquals("https://jmp2.uk/stvp-IN1", stream.url)
            assertEquals("okhttp/4.12.0", stream.headers["User-Agent"])
            assertTrue(stream.isLive)
        }
    }

    @Test fun plexLiveListsChannelsByTheirRegionsField() = runBlocking<Unit> {
        TestServer().use { s ->
            s.on("/Plex/.channels.json.gz") { MockResponse().setBody(gz("""{"regions":{"us":{"name":"USA"}},"channels":{
                "a-1":{"name":"Stories","regions":["us"],"programs":[[1793959920,"Hell on Wheels"]]},"b-2":{"name":"Elsewhere","regions":["gb"]}}}""")) }
            s.json("/api/v2/users/anonymous", """{"authToken":"anon-token"}""")
            val plugin = FreeServices.plex(s.http(), FreeLiveLists(s.base) { listOf("us") }, "client", s.base, s.base, s.base)
            val channels = plugin.channels()
            assertEquals(listOf("Stories"), channels.map { it.name })
            val stream = plugin.streams(PlayTarget.Live(channels[0])).single()
            assertEquals("${s.base}/library/parts/a-1.m3u8", stream.url)
            assertEquals("anon-token", stream.headers["X-Plex-Token"])
            assertTrue(!stream.isPublic, "a stream with a token never travels in a room")
        }
    }
}
