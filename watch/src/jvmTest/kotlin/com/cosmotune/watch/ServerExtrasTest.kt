package com.cosmotune.watch

import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchChapter
import com.cosmotune.watch.model.WatchSkip
import com.cosmotune.watch.plugins.servers.JellyfinPlugin
import com.cosmotune.watch.plugins.servers.PlexPlugin
import com.cosmotune.watch.plugins.servers.ServerSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerExtrasTest {
    private fun items(text: String) = Json.parseToJsonElement(text).jsonArray.toList()

    @Test fun jellyfinSegmentsBecomeSkipsAndCommercialsAreLeftOut() {
        val segments = items("""[{"Type":"Intro","StartTicks":50000000,"EndTicks":900000000},{"Type":"Commercial","StartTicks":1,"EndTicks":2},
            {"Type":"Outro","StartTicks":25000000000,"EndTicks":99000000000},{"Type":"Recap","StartTicks":5,"EndTicks":5}]""")
        assertEquals(listOf(WatchSkip(5_000, 90_000, "Skip intro"), WatchSkip(2_500_000, 2_600_000, "Skip credits")), JellyfinPlugin.segmentsOf(segments, 2_600_000))
    }

    @Test fun embyMarkersAndNamedChapters() {
        val chapters = items("""[{"StartPositionTicks":0,"Name":"Opening"},{"StartPositionTicks":300000000,"MarkerType":"IntroStart"},
            {"StartPositionTicks":1200000000,"MarkerType":"IntroEnd"},{"StartPositionTicks":6000000000,"Name":"The heist","MarkerType":"Chapter"},
            {"StartPositionTicks":24000000000,"MarkerType":"CreditsStart"}]""")
        assertEquals(listOf(WatchSkip(30_000, 120_000, "Skip intro"), WatchSkip(2_400_000, 2_500_000, "Skip credits")), JellyfinPlugin.markersOf(chapters, 2_500_000))
        assertEquals(listOf(WatchChapter(0, "Opening"), WatchChapter(600_000, "The heist")), JellyfinPlugin.chaptersOf(chapters))
    }

    @Test fun plexMarkersAndChapters() {
        val item = Json.parseToJsonElement("""{"Marker":[{"type":"intro","startTimeOffset":1000,"endTimeOffset":61000},{"type":"credits","startTimeOffset":2000000}],
            "Chapter":[{"tag":"Act one","startTimeOffset":0,"index":1},{"startTimeOffset":900000,"index":2}]}""").jsonObject
        val extras = PlexPlugin.extrasOf(item, 2_200_000)
        assertEquals(listOf(WatchSkip(1_000, 61_000, "Skip intro"), WatchSkip(2_000_000, 2_200_000, "Skip credits")), extras.skips)
        assertEquals(listOf(WatchChapter(0, "Act one"), WatchChapter(900_000, "Chapter 2")), extras.chapters)
    }

    @Test fun jellyfinAsksItsOwnServerForTheCopy() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/MediaSegments/m1", """{"Items":[{"Type":"Intro","StartTicks":0,"EndTicks":600000000}]}""")
            s.json("/Users/u1/Items/m1", """{"Id":"m1","Chapters":[{"StartPositionTicks":0,"Name":"Start"},{"StartPositionTicks":3000000000,"Name":"Middle"}]}""")
            val plugin = JellyfinPlugin("jf-1", "Home", s.http(), s.base, ServerSession("tok", "u1"))
            val stream = Stream(StreamSource("jf-1", "Home", "JF"), "Direct play", url = "${s.base}/Videos/m1/stream", protocol = StreamProtocol.PROGRESSIVE, serverItemId = "m1")
            val extras = plugin.playbackExtras(stream, 5_000_000)
            assertEquals(listOf(WatchSkip(0, 60_000, "Skip intro")), extras.skips)
            assertEquals(listOf("Start", "Middle"), extras.chapters.map { it.title })
            assertTrue(s.requests.all { it.getHeader("X-Emby-Token") == "tok" })
        }
    }
}
