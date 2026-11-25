package com.cosmotune.watch

import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An add-on that names its own kind of list, as CNCVerse does: "other" catalogues mixing films and series (an anime with episodes),
 * with "tv" lists beside them for Live TV. Its ids are its own ("cnc:…"), and it answers whichever kind it is asked for.
 */
class StremioAnyKindCatalogTest {
    @Test fun liveGuideUsesDatedPagesAndKeepsTheChannelIdentity() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", """{"id":"org.fixture.tv","types":["tv"],"resources":["catalog","meta","stream"],
                "behaviorHints":{"epgProvider":true},"catalogs":[{"type":"tv","id":"live","extra":[{"name":"date"},{"name":"skip"}]}]}""")
            s.json("/catalog/tv/live.json", """{"metas":[{"id":"fixture:news","name":"Fixture News","type":"tv"}]}""")
            s.json("/catalog/tv/live/date=2026-09-12.json", """{"metasDetailed":[{"id":"fixture:news","videos":[
                {"id":"on","title":"Evening News","startTime":"2026-09-12T23:30:00Z","endTime":"2026-09-13T00:15:00Z"}
            ]}]}""")
            s.json("/catalog/tv/live/date=2026-09-13.json", """{"metasDetailed":[{"id":"fixture:news","videos":[
                {"id":"on","title":"Evening News","startTime":"2026-09-12T23:30:00Z","endTime":"2026-09-13T00:15:00Z"},
                {"id":"next","title":"Weather","startTime":"2026-09-13T00:15:00Z","endTime":"2026-09-13T00:45:00Z"},
                {"id":"bad","title":"Bad entry","startTime":"2026-09-13T00:45:00Z","endTime":"2026-09-13T00:45:00Z"}
            ]}]}""")
            val plugin = StremioPlugin("st-fixture", "Fixture", "TV", StremioClient(s.http(), s.url("/manifest.json")),
                setOf(Capability.LIVE, Capability.EPG))
            val channel = plugin.channels().single()
            val from = java.time.Instant.parse("2026-09-12T23:45:00Z").toEpochMilli()
            val to = java.time.Instant.parse("2026-09-13T01:00:00Z").toEpochMilli()
            val programmes = plugin.guide(listOf(channel), from, to)
            assertEquals(channel.key.id, channel.epgId)
            assertEquals(listOf("Evening News", "Weather"), programmes.map { it.title })
            assertTrue(programmes.first().isOnAt(from))
            assertTrue(programmes.all { it.epgId == channel.epgId })
        }
    }

    private val manifest = """{"id":"org.cnc","name":"Bridge","version":"1.0.0","resources":["catalog","meta","stream"],
        "types":["movie","series","other","tv"],"catalogs":[
        {"type":"other","id":"mix","name":"Netflix (other)","extra":[{"name":"skip"}]},
        {"type":"tv","id":"jio","name":"JIO TV (tv)"},
        {"type":"other","id":"needs","name":"Needs genre","extra":[{"name":"genre","isRequired":true}]}]}"""

    @Test fun aListOfTheAddOnsOwnKindIsAHomeRowAndItsTitlesOpenAsFilmsOrSeries() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", manifest)
            s.json("/catalog/other/mix", """{"metas":[{"id":"cnc:film1","type":"other","name":"Dhamaal 4","poster":"https://p/1.jpg"},
                {"id":"cnc:anime1","type":"other","name":"Attack on Titan","poster":"https://p/2.jpg"}]}""")
            s.json("/catalog/tv/jio", """{"metas":[{"id":"cnc:ch1","type":"tv","name":"Sun TV","poster":"https://p/sun.png"}]}""")
            s.json("/meta/movie/cnc:anime1", """{"meta":{"id":"cnc:anime1","type":"other","name":"Attack on Titan",
                "videos":[{"id":"cnc:anime1:1:1","season":1,"episode":1,"title":"To You, in 2000 Years"},{"id":"cnc:anime1:1:2","season":1,"episode":2,"title":"That Day"}]}}""")
            s.json("/meta/series/cnc:anime1", """{"meta":{"id":"cnc:anime1","type":"other","name":"Attack on Titan",
                "videos":[{"id":"cnc:anime1:1:1","season":1,"episode":1,"title":"To You, in 2000 Years"},{"id":"cnc:anime1:1:2","season":1,"episode":2,"title":"That Day"}]}}""")
            s.json("/meta/movie/cnc:film1", """{"meta":{"id":"cnc:film1","type":"other","name":"Dhamaal 4","videos":[]}}""")
            s.json("/stream/movie/cnc:film1", """{"streams":[{"name":"CNC 1080p","url":"https://cdn.example/f.m3u8"}]}""")
            s.json("/stream/series/cnc:anime1:1:2", """{"streams":[{"name":"ReAnime 1080p","url":"https://cdn.example/a2.m3u8"}]}""")
            val plugin = StremioPlugin("st-cnc", "Bridge", "CNC", StremioClient(s.http(), s.url("/manifest.json")),
                setOf(Capability.CATALOG, Capability.SEARCH, Capability.META, Capability.STREAMS, Capability.LIVE))

            val catalogs = plugin.catalogs()
            assertEquals(listOf("other/mix"), catalogs.map { it.id }, "the TV list is Live TV's, and one needing a genre is no row")
            assertTrue(catalogs.single().anyKind && catalogs.single().type == null)

            val page = plugin.catalogPage(catalogs.single())
            assertEquals(listOf("Dhamaal 4", "Attack on Titan"), page.map { it.name }, "an \"other\" title is no longer dropped")
            assertTrue(page.all { it.type == TitleType.FILM }, "a list does not say which are series")

            val anime = assertNotNull(plugin.title(page[1].key), "the add-on's own title opens")
            assertEquals(TitleType.SERIES, anime.summary.type, "its page lists episodes, so it is a series")
            assertEquals(listOf(1), anime.seasons.map { it.number })
            val episodes = plugin.episodes(anime.summary.key, 1)
            assertEquals(listOf("To You, in 2000 Years", "That Day"), episodes.map { it.name })
            val second = plugin.streams(PlayTarget.EpisodeOf(episodes[1].key, ExternalIds(), "Attack on Titan", 1, 2))
            assertEquals("https://cdn.example/a2.m3u8", second.single().url)

            val film = assertNotNull(plugin.title(page[0].key))
            assertEquals(TitleType.FILM, film.summary.type)
            val copies = plugin.streams(PlayTarget.Film(page[0].key, ExternalIds(), "Dhamaal 4", null))
            assertEquals("https://cdn.example/f.m3u8", copies.single().url)

            assertEquals(listOf("Sun TV"), plugin.channels().map { it.name }, "its TV lists are channels")
        }
    }

    @Test fun anotherKindsTitleInAFilmListIsStillLeftOut() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", """{"id":"org.t","name":"T","resources":["catalog"],"types":["movie"],"catalogs":[{"type":"movie","id":"top","name":"Top"}]}""")
            s.json("/catalog/movie/top", """{"metas":[{"id":"tt1","type":"movie","name":"Film"},{"id":"x","type":"other","name":"Skip"}]}""")
            val plugin = StremioPlugin("st-t", "T", "T", StremioClient(s.http(), s.url("/manifest.json")), setOf(Capability.CATALOG))
            assertEquals(listOf("Film"), plugin.catalogPage(plugin.catalogs().single()).map { it.name })
        }
    }
}
