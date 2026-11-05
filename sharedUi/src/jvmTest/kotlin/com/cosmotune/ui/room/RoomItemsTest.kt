package com.cosmotune.ui.room

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.PodcastEpisode
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomItemsTest {
    private val station = RadioStation(
        id = "96062a7b-0601-11e8-ae97-52543be04c81", name = "Radio City Tamil", url = "https://stream.example.in/city.mp3",
        country = "India", state = "Tamil Nadu", artwork = "https://example.in/logo.png",
    )
    private val episode = PodcastEpisode(
        id = "0123456789abcdef01234567", title = "Episode 12", show = "The Tamil Show", url = "https://cdn.example.com/e12.mp3",
        durationSeconds = 1_800,
    )

    @Test fun aVideoIsAVideoWithItsChannelAndLength() {
        val item = assertNotNull(roomVideoItem("dQw4w9WgXcQ", "  A video  ", "A channel", "https://i.ytimg.com/vi/x/hq.jpg", 213_000))
        assertEquals(PartyTrack.KIND_VIDEO, item.kind)
        assertEquals("dQw4w9WgXcQ", item.videoId)
        assertEquals("A video", item.title)
        assertEquals("A channel", item.artist)
        assertEquals(213_000L, item.durationMs)
        assertNull(item.url)
        assertNull(roomVideoItem("  ", "t", "c", null, null), "no id, no item")
        assertNull(roomVideoItem("live", "t", "c", null, 0)!!.durationMs, "a live video has no length")
    }

    @Test fun aStationIsLiveWithItsPlaceAndHttpsStream() {
        val item = assertNotNull(station.roomItem())
        assertEquals(PartyTrack.KIND_RADIO, item.kind)
        assertTrue(item.isLive)
        assertEquals("podio:radio:96062a7b-0601-11e8-ae97-52543be04c81", item.videoId)
        assertTrue(item.videoId.length <= ROOM_ITEM_ID_MAX)
        assertEquals("Radio City Tamil", item.title)
        assertEquals("Tamil Nadu, India", item.artist)
        assertEquals("https://stream.example.in/city.mp3", item.url)
        assertNull(item.durationMs)
        // The same station from Podio's player (an item with the id already prefixed) is the same room item.
        assertEquals(item.videoId, station.item().roomItem()!!.videoId)
    }

    @Test fun aStationThatIsNotHttpsHasNoItem() {
        assertNull(station.copy(url = "http://stream.example.in/city.mp3").roomItem())
        assertNull(station.copy(url = "https://").roomItem())
        assertNull(station.copy(url = "https://user:pw@stream.example.in/x").roomItem())
        assertNull(station.copy(url = "https://example.in/" + "a".repeat(ROOM_ITEM_URL_MAX)).roomItem(), "a link is never cut short")
        assertEquals("https://Stream.example.in/x", roomHttpsUrl(" HTTPS://Stream.example.in/x "))
    }

    @Test fun anEpisodeCarriesItsShowEnclosureAndLength() {
        val item = assertNotNull(episode.roomItem(artworkFallback = "https://example.com/show.png"))
        assertEquals(PartyTrack.KIND_PODCAST, item.kind)
        assertEquals("podio:episode:0123456789abcdef01234567", item.videoId)
        assertEquals("The Tamil Show", item.artist)
        assertEquals("https://cdn.example.com/e12.mp3", item.url)
        assertEquals(1_800_000L, item.durationMs)
        assertEquals("https://example.com/show.png", item.thumbnailUrl)
        assertEquals(item, episode.item().copy(artwork = "https://example.com/show.png").roomItem())
        assertNull(episode.copy(url = "http://cdn.example.com/e12.mp3").roomItem())
    }

    @Test fun aLongIdIsHashedToTheSameShortIdEveryTime() {
        val guid = "tag:example.com,2024:" + "x".repeat(120)
        val a = assertNotNull(roomEpisodeItem(guid, "t", "s", "https://e.com/a.mp3", null, null)).videoId
        val b = assertNotNull(roomEpisodeItem(guid, "t", "s", "https://e.com/a.mp3", null, null)).videoId
        assertEquals(a, b)
        assertEquals(ROOM_ITEM_ID_MAX, a.length)
        assertTrue(a.startsWith(ROOM_EPISODE_PREFIX), a)
        assertNotEquals(a, roomEpisodeItem(guid + "y", "t", "s", "https://e.com/a.mp3", null, null)!!.videoId)
        assertEquals("short", roomItemId("short"))
    }

    @Test fun textIsCutToTheRoomsLimits() {
        val item = assertNotNull(roomVideoItem("v", "t".repeat(400), "c".repeat(400), "ftp://x/y.png", null))
        assertEquals(ROOM_ITEM_TEXT_MAX, item.title.length)
        assertEquals(ROOM_ITEM_TEXT_MAX, item.artist.length)
        assertNull(item.thumbnailUrl, "artwork the others cannot fetch is dropped")
    }

    @Test fun aPodioItemBecomesAStationOrAnEpisodeByWhetherItIsLive() {
        assertEquals(PartyTrack.KIND_RADIO, PodioItem("podio:radio:abc", "S", "Place", "https://s.example/x", live = true).roomItem()!!.kind)
        assertEquals(PartyTrack.KIND_PODCAST, PodioItem("podio:episode:abc", "E", "Show", "https://s.example/x", durationSeconds = 60).roomItem()!!.kind)
    }

    @Test fun theLabelFollowsTheRoomAndTheKind() {
        assertEquals(RoomPlayLabel.PLAY_IN_ROOM, roomPlayLabel(inRoom = true, canControl = true, video = true))
        assertEquals(RoomPlayLabel.HOST_ONLY, roomPlayLabel(inRoom = true, canControl = false, video = false))
        assertEquals(RoomPlayLabel.WATCH_TOGETHER, roomPlayLabel(inRoom = false, canControl = false, video = true))
        assertEquals(RoomPlayLabel.LISTEN_TOGETHER, roomPlayLabel(inRoom = false, canControl = false, video = false))
    }
}
