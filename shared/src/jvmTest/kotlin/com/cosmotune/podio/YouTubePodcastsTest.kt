package com.cosmotune.podio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class YouTubePodcastsTest {
    private val showId = "MPSPPLpublic_show_1234"
    private val videoId = "abcdefghijk"
    private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun episode(id: String, title: String): String = """{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"$title"}]},
        "onTap":{"watchEndpoint":{"videoId":"$id"}},"playbackProgress":{"musicPlaybackProgressRenderer":{"durationText":{"runs":[{"text":"1:02:03"}]}}},
        "subtitle":{"runs":[{"text":"2 days ago"}]}}}"""

    @Test fun sharedPlaylistLinksNeedActualPodcastEvidenceAndDoNotGoToUnrelatedDirectories() = runBlocking<Unit> {
        val playlist = "PLpublic_show_1234"
        val address = "https://music.youtube.com/playlist?list=$playlist&si=share"
        assertEquals("MPSP$playlist", YouTubePodcasts.showId(address))
        assertEquals("MPSP$playlist", YouTubePodcasts.showId("https://www.youtube.com/playlist?list=%50%4Cpublic_show_1234"))
        assertNull(YouTubePodcasts.showId("https://youtube.com.evil.example/playlist?list=$playlist"))
        assertNull(YouTubePodcasts.showId("https://youtube.com/playlist?list=$playlist&list=PLanother_show"))
        var marked = true
        var calls = 0
        val adapter = YouTubePodcasts { endpoint, body ->
            calls++
            assertEquals("browse", endpoint)
            assertEquals("MPSP$playlist", body["browseId"]?.jsonPrimitive?.content)
            parse("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Stories"}]}}},"items":[
                {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"An episode"}]},"onTap":{"watchEndpoint":{"videoId":"$videoId"}},
                "musicVideoType":"${if (marked) "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" else "MUSIC_VIDEO_TYPE_ATV"}"}}]}""")
        }
        val catalogue = PodioCatalogue(appleBase = "https://unreachable.invalid", fyydBase = "https://unreachable.invalid", youtubePodcasts = adapter)
        assertEquals("Stories", catalogue.podcasts(address).single().title)
        assertEquals(1, calls)
        marked = false
        assertFailsWith<IllegalArgumentException> { catalogue.feed(address) }
    }

    @Test fun publicShowCardsExcludeOrdinaryMusicAndRetainSourceIdentity() {
        val root = parse("""{"items":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Stories"}]},"subtitle":{"runs":[{"text":"Host"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"$showId"}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"A music album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREbalbum"}}}}]}""")
        val show = YouTubePodcasts.parseShows(root).single()
        assertEquals("Stories", show.title)
        assertEquals(showId, YouTubePodcasts.showId(show.feed))
        assertTrue(show.source.contains("public podcast"))
        assertNull(YouTubePodcasts.showId("https://music.youtube.com.evil.example/browse/$showId"))
        assertNull(YouTubePodcasts.showId("https://user:secret@music.youtube.com/browse/$showId"))
    }

    @Test fun episodesKeepPodioIdentityAndWatchAddressesAreNeverDirectAudio() {
        val root = parse("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Stories"}]}}},"items":[${episode(videoId, "A conversation")}]}""")
        val feed = YouTubePodcasts.parseFeed(root, showId)
        val episode = feed.episodes.single()
        assertEquals(3723, episode.durationSeconds)
        val song = episode.item().song()
        assertEquals("podio:episode:youtube:$videoId", song.videoId)
        assertTrue(song.isPodio)
        assertNull(song.localUri, "A YouTube watch page is not an enclosure or a decoder input")
        assertEquals(videoId, YouTubePodcasts.videoId(song.videoId))
        assertEquals("cosmotune://podio-youtube?v=$videoId", YouTubePodcasts.playbackUri(song.videoId))
        assertNull(YouTubePodcasts.videoId("podio:episode:youtube:../private"))
        assertNull(YouTubePodcasts.videoId(videoId))
        assertEquals("YouTube Music podcast", podioSourceName(song.videoId))
        assertEquals("YouTube Music podcast", podioSourceName("podio:youtube"))
        val injectedMenu = parse("""{"items":[{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Not a playable row"}]},"menu":{"watchEndpoint":{"videoId":"$videoId"}}}}]}""")
        assertTrue(YouTubePodcasts.parseFeed(injectedMenu, showId).episodes.isEmpty())
    }

    @Test fun showContinuationUsesTheOpaqueTokenAndPreservesTheOriginalShow() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val adapter = YouTubePodcasts { endpoint, body ->
            calls += endpoint to body
            if (body["continuation"] == null) parse("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Stories"}]}}},
                "items":[${episode(videoId, "First episode")}],"continuations":[{"nextContinuationData":{"continuation":"opaque+/token="}}]}""")
            else parse("""{"items":[${episode("lmnopqrstuv", "Older episode")},${episode(videoId, "Duplicate")}]}""")
        }
        val catalogue = PodioCatalogue(youtubePodcasts = adapter)
        val first = catalogue.feed("https://music.youtube.com/browse/$showId")
        val archive = catalogue.moreEpisodes(first)
        assertEquals(listOf("First episode", "Older episode"), archive.episodes.map { it.title })
        assertEquals("Stories", archive.show.title)
        assertEquals("opaque+/token=", calls.last().second["continuation"]?.jsonPrimitive?.content)
        assertNull(archive.nextPage)
    }
}
