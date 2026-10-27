package com.cosmotune.podio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.*

class PodioCatalogueTest {
    @Test fun languageLabelsAreNormalizedAndFilteredResultsAreNotTruncatedAtOneHundred() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            PodioCatalogue(radioMirrors = listOf(server.url("/").toString())).stations(countryCode = "in", language = " Tamil ")
            val url = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("tamil", url.queryParameter("language"))
            assertEquals("IN", url.queryParameter("countrycode"))
            assertEquals("3000", url.queryParameter("limit"))
        }
    }
    @Test fun publisherFeedsProducePlayableAudioWithStableIdentitiesAndDurations() {
        val feed = PodioCatalogue.parseFeed("""<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
            <title>Stories</title><itunes:author>A host</itunes:author><itunes:image href="/cover.jpg"/>
            <item><guid>episode-1</guid><title>Episode one</title><enclosure url="https://cdn.example/audio.mp3" type="audio/mpeg"/>
            <itunes:duration>1:02:03</itunes:duration><description><![CDATA[<b>A story</b>]]></description></item>
            <item><guid>episode-1</guid><title>Duplicate</title><enclosure url="https://cdn.example/audio.mp3"/></item>
            <item><title>Not audio</title><enclosure url="https://cdn.example/video.mp4" type="video/mp4"/></item>
            <item><title>Unsafe</title><enclosure url="file:///etc/passwd"/></item>
            </channel></rss>""", "https://example.com/feed.xml")
        assertEquals("https://example.com/cover.jpg", feed.show.artwork)
        assertEquals(1, feed.episodes.size)
        val episode = feed.episodes.single()
        assertEquals(3723, episode.durationSeconds)
        assertEquals("A story", episode.description)
        assertTrue(episode.item().song().isPodio)
        assertEquals("https://cdn.example/audio.mp3", episode.item().song().localUri)
        assertEquals(episode.id, PodioCatalogue.parseFeed("""<rss><channel><title>Stories</title><item><guid>episode-1</guid><title>Updated title</title><enclosure url="https://cdn.example/updated.mp3"/></item></channel></rss>""", "https://example.com/feed.xml").episodes.single().id)
        assertEquals(0, PodioCatalogue.duration("bad"))
    }

    @Test fun xmlEntitiesAndNonHttpEnclosuresNeverBecomePlayable() {
        assertFails { PodioCatalogue.parseFeed("""<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///private">]><rss><channel><title>&x;</title></channel></rss>""", "https://example.com/feed") }
        assertNull(PodioCatalogue.httpUrl("javascript:alert(1)"))
        assertNull(PodioCatalogue.httpUrl("https://user:password@example.com/feed"))
        assertEquals("http://station.example/live", PodioCatalogue.httpUrl("http://station.example/live"))
    }

    @Test fun radioMirrorFailureUsesAnotherMirrorAndRetainsCoordinates() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody("""[{"stationuuid":"abc-123","name":"Station","url_resolved":"https://station.example/live","geo_lat":13.08,"geo_long":80.27,"codec":"MP3","bitrate":128}]"""))
            val result = PodioCatalogue(radioMirrors = listOf(first.url("/").toString(), second.url("/").toString())).stations(globe = true).single()
            assertEquals(13.08, result.latitude)
            assertEquals(80.27, result.longitude)
            assertTrue(result.item().live)
            assertEquals("true", second.takeRequest().requestUrl?.queryParameter("has_geo_info"))
        } }
    }

    @Test fun podcastDirectoriesDeduplicateFeedsAndOneFailureDoesNotHideTheOther() = runBlocking {
        MockWebServer().use { apple -> MockWebServer().use { fyyd ->
            apple.enqueue(MockResponse().setBody("""{"results":[{"collectionName":"Stories","artistName":"Host","feedUrl":"https://example.com/feed"}]}"""))
            fyyd.enqueue(MockResponse().setBody("""{"data":[{"title":"Stories","author":"Host","xmlURL":"https://example.com/feed"}]}"""))
            val catalogue = PodioCatalogue(appleBase = apple.url("/").toString().trimEnd('/'), fyydBase = fyyd.url("/").toString().trimEnd('/'))
            assertEquals(1, catalogue.podcasts("stories").size)
            apple.enqueue(MockResponse().setResponseCode(503))
            fyyd.enqueue(MockResponse().setBody("""{"data":[{"title":"Stories","author":"Host","xmlURL":"https://example.com/feed"}]}"""))
            assertEquals("Stories", catalogue.podcasts("stories").single().title)
        } }
    }

    @Test fun favouritesFollowsAndListeningProgressSurviveReopening() {
        val folder = Files.createTempDirectory("podio-library").toFile()
        try {
            val file = folder.resolve("library.json")
            val library = PodioLibrary(file)
            val station = RadioStation("one", "Station", "https://example.com/live")
            library.update { it.copy(stations = listOf(station), progress = mapOf("episode" to 15000L)) }
            assertEquals(listOf(station), PodioLibrary(file).state.value.stations)
            assertEquals(15000, PodioLibrary(file).state.value.progress["episode"])
        } finally { folder.deleteRecursively() }
    }

    @Test fun spotifyLinksUseMatchingPublisherEpisodesAndRejectTimingMismatches(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val metadata = PodcastFeed(PodcastShow("spotify:show", "Stories", "Host", "https://open.spotify.com/show/1234567890123456789012"),
                listOf(PodcastEpisode("spotify:episode", "A story – part one", "Stories", "https://open.spotify.com/episode/1234567890123456789012", durationSeconds = 1000)))
            var duration = "1000"
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().setBody(when {
                    request.path.orEmpty().startsWith("/search") -> """{"results":[{"collectionName":"Stories","feedUrl":"$base/feed"}]}"""
                    request.path.orEmpty().startsWith("/0.2") -> """{"data":[]}"""
                    else -> """<rss><channel><title>Stories</title><item><guid>one</guid><title>A story: part one</title><duration>$duration</duration><enclosure url="https://publisher.example/audio.mp3" type="audio/mpeg"/></item></channel></rss>"""
                })
            }
            val catalogue = PodioCatalogue(appleBase = base, fyydBase = base, spotifyMetadata = { metadata })
            val address = "https://open.spotify.com/episode/1234567890123456789012"
            val result = catalogue.feed(address)
            assertEquals("https://publisher.example/audio.mp3", result.episodes.single().url)
            assertTrue(result.show.source.contains("Publisher RSS"))
            assertFalse(result.episodes.single().id.startsWith("spotify:"))
            duration = "4000"
            assertFailsWith<IllegalStateException> { catalogue.feed(address) }
        }
    }
}
