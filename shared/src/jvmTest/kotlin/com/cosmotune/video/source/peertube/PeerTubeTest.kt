package com.cosmotune.video.source.peertube

import com.cosmotune.video.local.LocalVideo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PeerTube's public API answers, written in the shape the API documents (hand-made samples). */
class PeerTubeTest {
    private val ref = PeerChannelRef("tube.example.org", "deskl")

    @Test fun channelAddressesAreRead() {
        assertEquals(ref, PeerTubeClient.parseChannel("https://tube.example.org/c/deskl/videos"))
        assertEquals(ref, PeerTubeClient.parseChannel("tube.example.org/video-channels/deskl"))
        assertEquals(ref, PeerTubeClient.parseChannel("deskl@tube.example.org"))
        assertEquals(PeerChannelRef("other.example", "x"), PeerTubeClient.parseChannel("https://tube.example.org/c/x@other.example"))
        assertNull(PeerTubeClient.parseChannel("https://tube.example.org/w/abc"))
        assertNull(PeerTubeClient.parseChannel("nonsense"))
    }

    @Test fun videosBecomeItemsThatPlayAsFiles() {
        val page = Json.parseToJsonElement(
            """{"total":1,"data":[{"uuid":"9c9de5e8-0a1e-484a-b099-e80766180a6d","name":"Desk tour","duration":600,"views":42,
              "publishedAt":"2024-05-01T12:00:00.000Z","thumbnailPath":"/lazy-static/thumbnails/a.jpg","isLive":false,
              "channel":{"name":"deskl","displayName":"Desk Lab","avatars":[{"path":"/a/s.png","width":48},{"path":"/a/l.png","width":120}]}}]}""",
        ) as JsonObject
        val item = PeerTubeClient.parseVideos(page, ref).single()
        assertTrue(LocalVideo.isLocal(item.id) && PeerTubeClient.isPeerTube(item.id))
        assertEquals("tube.example.org" to "9c9de5e8-0a1e-484a-b099-e80766180a6d", PeerTubeClient.parseId(item.id))
        assertEquals("Desk Lab", item.channelName)
        assertEquals("https://tube.example.org/lazy-static/thumbnails/a.jpg", item.thumbnailUrl)
        assertEquals("https://tube.example.org/a/l.png", item.channelAvatarUrl)
        assertEquals(1_714_564_800_000, item.uploadedAtMs)
        assertEquals(600L, item.durationSec)
    }

    @Test fun theSharpestFileUpTo1080pPlays() {
        val video = Json.parseToJsonElement(
            """{"files":[],"streamingPlaylists":[{"playlistUrl":"https://x/m.m3u8","files":[
              {"resolution":{"id":480},"fileUrl":"https://x/480.mp4"},{"resolution":{"id":1080},"fileUrl":"https://x/1080.mp4"},
              {"resolution":{"id":2160},"fileUrl":"https://x/2160.mp4"},{"resolution":{"id":0},"fileUrl":"https://x/audio.mp4"}]}]}""",
        ) as JsonObject
        assertEquals("https://x/1080.mp4", PeerTubeClient.bestFile(video))
        assertNull(PeerTubeClient.bestFile(Json.parseToJsonElement("""{"files":[]}""") as JsonObject))
    }
}
