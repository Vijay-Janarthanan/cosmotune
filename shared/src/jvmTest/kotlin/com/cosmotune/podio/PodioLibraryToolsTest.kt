package com.cosmotune.podio

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class PodioLibraryToolsTest {
    @Test fun utf8ByteOrderMarksAreAcceptedWithoutRelaxingXmlEntityProtection() {
        val feed = PodioCatalogue.parseFeed("\uFEFF<?xml version=\"1.0\"?><rss><channel><title>Stories</title></channel></rss>", "https://publisher.example/feed")
        assertEquals("Stories", feed.show.title)
        val opml = "\uFEFF<?xml version=\"1.0\"?><opml><body><outline text=\"Stories\" xmlUrl=\"https://publisher.example/feed\"/></body></opml>"
        assertEquals("Stories", PodioOpml.decode(opml).single().title)
        assertFails { PodioOpml.decode("\uFEFF<!DOCTYPE opml><opml><body/></opml>") }
    }
    @Test fun archivePagesKeepTheOriginalEpisodeIdsDeduplicateAndStopCycles() = runBlocking {
        MockWebServer().use { server ->
            val root = server.url("/feed").toString()
            fun xml(guid: String, next: String, extra: String = "") = """<rss xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Stories</title>
                <atom:link rel="next" href="$next"/><item><guid>$guid</guid><title>Episode $guid</title><enclosure url="/audio/$guid.mp3" type="audio/mpeg"/></item>$extra</channel></rss>"""
            server.enqueue(MockResponse().setBody(xml("one", "/archive?page=2")))
            server.enqueue(MockResponse().setBody(xml("two", "/feed", """<item><guid>one</guid><title>Duplicate one</title><enclosure url="/audio/one.mp3"/></item>""")))
            val catalogue = PodioCatalogue()
            val first = catalogue.feed(root)
            assertEquals(server.url("/archive?page=2").toString(), first.nextPage)
            val archive = catalogue.moreEpisodes(first)
            assertEquals(listOf("Episode one", "Episode two"), archive.episodes.map { it.title })
            assertEquals(first.episodes.single().id, archive.episodes.first().id)
            assertEquals(root, archive.show.feed)
            assertNull(archive.nextPage)
            assertEquals(2, archive.visitedPages.size)
            assertEquals(2, server.requestCount)
            assertEquals(archive, catalogue.moreEpisodes(archive))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun atomArchiveLinksResolveAgainstThePageAndAFailedArchiveKeepsTheLoadedFeed() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val root = server.url("/podcast/feed").toString()
            val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Stories</title><link rel="next" href="older"/>
                <entry><id>one</id><title>First</title><link rel="enclosure" href="first.mp3" type="audio/mpeg"/></entry></feed>"""
            server.enqueue(MockResponse().setBody(xml))
            server.enqueue(MockResponse().setResponseCode(503))
            val catalogue = PodioCatalogue()
            val loaded = catalogue.feed(root)
            assertEquals(server.url("/podcast/older").toString(), loaded.nextPage)
            assertFailsWith<IllegalStateException> { catalogue.moreEpisodes(loaded) }
            assertEquals("First", loaded.episodes.single().title)
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Another show</title></channel></rss>"""))
            assertFailsWith<IllegalArgumentException> { catalogue.moreEpisodes(loaded) }
        }
    }

    @Test fun opmlNestedSubscriptionsRoundTripWithEscapedTitlesAndNoPlaybackData() {
        val shows = PodioOpml.decode("""<opml version="2.0"><body><outline text="Folder"><outline type="rss" text="Stories &amp; talks" xmlUrl="https://publisher.example/rss?one=1&amp;two=2"/>
            <outline text="Duplicate" xmlUrl="https://publisher.example/rss?one=1&amp;two=2"/><outline text="Unsafe" xmlUrl="file:///private"/></outline></body></opml>""")
        assertEquals(1, shows.size)
        assertEquals("Stories & talks", shows.single().title)
        val exported = PodioOpml.encode(shows)
        assertEquals(shows.map { it.feed }, PodioOpml.decode(exported).map { it.feed })
        assertTrue(exported.contains("Stories &amp; talks"))
        assertFalse(exported.contains("progress"))
        assertFails { PodioOpml.decode("""<!DOCTYPE opml [<!ENTITY secret SYSTEM "file:///private">]><opml><body>&secret;</body></opml>""") }
        assertFails { PodioOpml.decode("""<rss><channel><title>Not OPML</title></channel></rss>""") }
    }

    @Test fun importsAndThePlaybackOwnerShareStateWithoutLosingFollowsOrFinalPositions() {
        val folder = Files.createTempDirectory("podio-tools").toFile()
        try {
            val file = folder.resolve("library.json")
            val page = PodioLibrary(file)
            val owner = PodioLibrary(file)
            page.update { it.copy(stations = listOf(RadioStation("station", "Station", "https://audio.example/live"))) }
            var now = 0L
            val tracker = PodioProgressTracker(owner) { now }
            tracker.observe("podio:episode:first", 15_000, true)
            now = 1_000
            tracker.observe("podio:episode:first", 16_700, true)
            page.importOpml("""<opml><body><outline text="Stories" xmlUrl="https://publisher.example/rss"/></body></opml>""")
            tracker.observe("podio:episode:second", 2_000, true)
            assertEquals(16_700, page.state.value.progress["podio:episode:first"])
            assertEquals("Stories", owner.state.value.shows.single().title)
            assertEquals("Station", owner.state.value.stations.single().name)
            now = 1_500
            tracker.observe("podio:episode:second", 3_500, true)
            tracker.close()
            assertEquals(3_500, PodioLibrary(file).state.value.progress["podio:episode:second"])
            assertEquals(16_700, page.resumePosition("podio:episode:first", 120_000))
            assertEquals(0, page.resumePosition("podio:episode:first", 17_000))
            assertEquals(0, page.importOpml(PodioOpml.encode(owner.state.value.shows)))
        } finally { folder.deleteRecursively() }
    }

    @Test fun loadingAndNonEpisodeItemsDoNotOverwriteResumePositions() {
        val folder = Files.createTempDirectory("podio-progress").toFile()
        try {
            val library = PodioLibrary(folder.resolve("library.json"))
            val tracker = PodioProgressTracker(library) { 0L }
            tracker.observe("podio:episode:first", 45_000, true)
            tracker.observe("podio:episode:first", 0, false, loading = true)
            tracker.observe("podio:radio:station", 500_000, true)
            tracker.observe("music", 20_000, true)
            tracker.close()
            assertEquals(mapOf("podio:episode:first" to 45_000L), library.state.value.progress)
        } finally { folder.deleteRecursively() }
    }

    @Test fun completedEpisodesRestartEvenWhenThePublisherDidNotStateADuration() {
        val folder = Files.createTempDirectory("podio-completion").toFile()
        try {
            val library = PodioLibrary(folder.resolve("library.json"))
            val tracker = PodioProgressTracker(library) { 0L }
            val id = "podio:episode:no-rss-duration"
            tracker.observe(id, 59_500, false, durationMs = 60_000)
            assertTrue(id in library.state.value.completedEpisodes)
            assertEquals(0, library.resumePosition(id, 0))
            tracker.observe(id, 60_000, false)
            tracker.observe("podio:episode:next", 0, false)
            assertTrue(id in library.state.value.completedEpisodes, "A transition's missing old duration cannot erase completion")
            tracker.observe(id, 2_000, false, durationMs = 60_000)
            assertFalse(id in library.state.value.completedEpisodes)
            assertEquals(2_000, library.resumePosition(id, 0))
            tracker.close()
        } finally { folder.deleteRecursively() }
    }

    @Test fun aShortTrailerOnlyBecomesPlayedNearItsActualEnd() {
        assertFalse(PodioPlaybackPolicy.isFinished(0, 5_000))
        assertFalse(PodioPlaybackPolicy.isFinished(2_000, 5_000))
        assertTrue(PodioPlaybackPolicy.isFinished(4_750, 5_000))
        assertFalse(PodioPlaybackPolicy.isFinished(60_000, 0))
        assertFalse(PodioPlaybackPolicy.isFinished(60_000, -1))
    }
}
