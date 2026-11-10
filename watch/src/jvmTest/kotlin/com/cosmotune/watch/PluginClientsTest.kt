package com.cosmotune.watch

import com.cosmotune.watch.meta.TmdbMetadata
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugins.archive.InternetArchivePlugin
import com.cosmotune.watch.plugins.local.ParsedName
import com.cosmotune.watch.plugins.servers.JellyfinPlugin
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginClientsTest {
    private val manifest = """{"id":"org.test","name":"Test add-on","version":"1.0.0","description":"Streams for tests",
        "resources":["catalog",{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]},"subtitles","meta"],
        "types":["movie","series"],"catalogs":[{"type":"movie","id":"top","name":"Top","extra":[{"name":"search"},{"name":"skip"}]},
        {"type":"movie","id":"needs","name":"Needs genre","extra":[{"name":"genre","isRequired":true}]}]}"""

    @Test fun normalisesInstallLinks() {
        assertEquals("https://addon.example/x/manifest.json", StremioClient.normalise("stremio://addon.example/x/manifest.json"))
        assertEquals("https://addon.example/x/manifest.json", StremioClient.normalise("https://addon.example/x/"))
        assertEquals("https://addon.example/manifest.json", InstallLinks.manifestUrl("cosmotune://watch/addon?url=https%3A%2F%2Faddon.example%2Fmanifest.json"))
        assertTrue(InstallLinks.isInstallLink("stremio://a.example/manifest.json"))
        assertTrue(InstallLinks.isInstallLink("https://a.example/manifest.json"))
        assertTrue(!InstallLinks.isInstallLink("https://a.example/page"))
    }

    @Test fun stremioKeepsPlayableStreamsAndDropsTorrents() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", manifest)
            s.json("/stream/movie/tt0133093.json", """{"streams":[
                {"name":"Test\n1080p","description":"The.Matrix.1999.1080p.BluRay.x265 HDR 2.1 GB","url":"https://cdn.example/m.mkv","durationMs":8160000,
                 "behaviorHints":{"bingeGroup":"test-1080","filename":"The.Matrix.1999.1080p.mkv","videoSize":2254857830,"proxyHeaders":{"request":{"Referer":"https://ref.example"}}}},
                {"name":"Torrent","infoHash":"abcdef0123456789","fileIdx":0},
                {"name":"Usenet","nzbUrl":"https://nzb.example/x.nzb"},
                {"name":"Trailer","ytId":"vKQi3bBA1y8"},
                {"name":"Netflix","externalUrl":"https://www.netflix.com/title/1"},
                {"name":"Live","url":"https://cdn.example/live/index.m3u8"}]}""")
            s.json("/stream/series/tt0944947:1:2.json", """{"streams":[{"name":"720p","url":"https://cdn.example/got-s1e2.mp4"}]}""")
            s.json("/subtitles/movie/tt0133093", """{"subtitles":[{"id":"1","url":"https://subs.example/en.srt","lang":"eng"},{"id":"2","url":"ftp://bad","lang":"fre"}]}""")
            s.json("/catalog/movie/top", """{"metas":[{"id":"tt0133093","type":"movie","name":"The Matrix","releaseInfo":"1999","imdbRating":"8.7","poster":"https://p/m.jpg"},
                {"id":"own:1","type":"movie","name":"Own Film"},{"id":"x","type":"other","name":"Skip"}]}""")
            val plugin = StremioPlugin("st-1", "Test add-on", "TEST", StremioClient(s.http(), s.url("/manifest.json")),
                setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.SUBTITLES))
            val target = PlayTarget.Film(WatchKey.tmdbFilm(603), ExternalIds(tmdb = 603, imdb = "tt0133093"), "The Matrix", 1999)
            val streams = plugin.streams(target)
            assertEquals(4, streams.size, "torrent and usenet streams are dropped")
            val mkv = streams[0]
            assertEquals(1080, mkv.height)
            assertEquals("hevc", mkv.videoCodec)
            assertEquals("HDR10", mkv.hdr)
            assertEquals("test-1080", mkv.bingeGroup)
            assertEquals(2254857830, mkv.sizeBytes)
            assertEquals(8_160_000L, mkv.durationMs)
            assertEquals("https://ref.example", mkv.headers["Referer"])
            assertEquals(StreamProtocol.YOUTUBE, streams[1].protocol)
            assertEquals(StreamProtocol.EXTERNAL, streams[2].protocol)
            assertEquals(StreamProtocol.HLS, streams[3].protocol)
            val episode = plugin.streams(PlayTarget.EpisodeOf(WatchKey.tmdbEpisode(1399, 1, 2), ExternalIds(imdb = "tt0944947"), "Game of Thrones", 1, 2))
            assertEquals("https://cdn.example/got-s1e2.mp4", episode.single().url)
            val subs = plugin.subtitles(target, mkv)
            assertEquals(listOf("https://subs.example/en.srt"), subs.map { it.url })
            assertTrue(s.paths().any { it.startsWith("/subtitles/movie/tt0133093/filename=The.Matrix.1999.1080p.mkv&videoSize=2254857830.json") })
            val catalogs = plugin.catalogs()
            assertEquals(listOf("movie/top"), catalogs.map { it.id }, "catalogues needing an extra are not rows")
            val page = plugin.catalogPage(catalogs[0])
            assertEquals(listOf(WatchKey("m:imdb:tt0133093"), WatchKey("m:st-1:own:1")), page.map { it.key })
            assertEquals("own:1", page[1].key.id)
            assertEquals(1999, page[0].year)
            val manifestParsed = plugin.manifest()
            assertEquals(listOf("Films", "Series", "Subtitles", "Catalogues"), manifestParsed.offers)
        }
    }

    @Test fun tmdbReadsTitlesWithKeyOrToken() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/3/movie/603", """{"id":603,"title":"The Matrix","release_date":"1999-03-30","runtime":136,"vote_average":8.2,"overview":"Neo.",
                "poster_path":"/p.jpg","backdrop_path":"/b.jpg","imdb_id":"tt0133093","genres":[{"id":28,"name":"Action"}],"status":"Released",
                "credits":{"cast":[{"id":6384,"name":"Keanu Reeves","character":"Neo","profile_path":"/k.jpg"}],"crew":[{"name":"Lana Wachowski","job":"Director"}]},
                "videos":{"results":[{"site":"YouTube","type":"Trailer","key":"vKQi3bBA1y8","name":"Trailer"}]},
                "recommendations":{"results":[{"id":604,"title":"The Matrix Reloaded","release_date":"2003-05-15"}]},
                "release_dates":{"results":[{"iso_3166_1":"IN","release_dates":[{"certification":"A"}]}]},
                "watch/providers":{"results":{"IN":{"link":"https://tmdb/watch","flatrate":[{"provider_name":"Netflix","logo_path":"/n.png"}]}}},
                "images":{"logos":[{"file_path":"/logo.png"}]}}""")
            s.json("/3/tv/1399/season/1", """{"episodes":[{"episode_number":1,"name":"Winter Is Coming","air_date":"2011-04-17","runtime":62,"still_path":"/s.jpg","id":63056}]}""")
            val key = TmdbMetadata(s.http(), "v3key", baseUrl = s.url("/3").removeSuffix("/"), imageBase = "https://img")
            val title = assertNotNull(key.title(WatchKey.tmdbFilm(603)))
            assertEquals("The Matrix", title.summary.name)
            assertEquals("tt0133093", title.summary.ids.imdb)
            assertEquals(136, title.summary.runtimeMin)
            assertEquals("https://img/w500/logo.png", title.summary.logoUrl)
            assertEquals("A", title.certification)
            assertEquals("Neo", title.cast.single().role)
            assertEquals("vKQi3bBA1y8", title.trailers.single().youTubeKey)
            assertEquals(WatchKey.tmdbFilm(604), title.moreLikeThis.single().key)
            assertEquals("Netflix", title.whereToWatch.single().name)
            assertTrue(s.requests.last().requestUrl!!.queryParameter("api_key") == "v3key")
            val token = TmdbMetadata(s.http(), "eyJtoken", baseUrl = s.url("/3").removeSuffix("/"))
            val eps = token.episodes(WatchKey.tmdbSeries(1399), 1)
            assertEquals(WatchKey("e:tmdb:1399:1:1"), eps.single().key)
            assertEquals("Bearer eyJtoken", s.requests.last().getHeader("Authorization"))
            assertEquals(null, s.requests.last().requestUrl!!.queryParameter("api_key"))
        }
    }

    @Test fun jellyfinSignsInAndMatchesByProviderIds() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/Users/AuthenticateByName", """{"AccessToken":"tok123","User":{"Id":"u1"}}""")
            s.json("/System/Info/Public", """{"ServerName":"Home"}""")
            s.json("/Users/u1/Items", """{"Items":[
                {"Id":"other","Type":"Movie","Name":"The Matrix","ProductionYear":2021,"ProviderIds":{"Tmdb":"624860"}},
                {"Id":"m1","Type":"Movie","Name":"The Matrix","ProductionYear":1999,"ProviderIds":{"Tmdb":"603","Imdb":"tt0133093"},
                 "MediaSources":[{"Id":"ms1","Container":"mkv","Size":58000000000,"Path":"/media/The.Matrix.2160p.mkv","RunTimeTicks":81600000000,
                   "MediaStreams":[{"Type":"Video","Height":2160,"Codec":"hevc","VideoRangeType":"HDR10"},
                     {"Type":"Audio","Index":1,"Language":"eng","DisplayTitle":"English 5.1","Codec":"eac3","Channels":6,"IsDefault":true},
                     {"Type":"Subtitle","Index":3,"Language":"eng","Codec":"subrip","IsExternal":true}]}]}]}""")
            val http = s.http()
            val session = JellyfinPlugin.signIn(http, s.base, "me", "secret", emby = false, deviceId = "dev")
            assertEquals("tok123", session.token)
            assertEquals("Home", session.serverName)
            assertTrue(s.requests.first().getHeader("Authorization")!!.startsWith("MediaBrowser Client=\"Cosmotune\""))
            val plugin = JellyfinPlugin("jf-1", "Home", http, s.base, session)
            val streams = plugin.streams(PlayTarget.Film(WatchKey.tmdbFilm(603), ExternalIds(tmdb = 603), "The Matrix", 1999))
            assertEquals(2, streams.size)
            val direct = streams[0]
            assertTrue(direct.url.contains("/Videos/m1/stream?static=true&MediaSourceId=ms1"))
            assertTrue("tok123" !in direct.url, "the token travels in a header")
            assertEquals("tok123", direct.headers["X-Emby-Token"])
            assertEquals(2160, direct.height)
            assertEquals("HDR10", direct.hdr)
            assertEquals("English 5.1", direct.audioTracks.single().label)
            assertEquals(8_160_000L, direct.durationMs)
            assertEquals("The.Matrix.2160p.mkv", direct.filename)
            assertEquals(1, direct.subtitles.size)
            assertEquals(StreamProtocol.HLS, streams[1].protocol)
        }
    }

    @Test fun archiveFindsPublicFiles() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/advancedsearch.php", """{"response":{"docs":[{"identifier":"nosferatu1922","title":"Nosferatu","year":"1922","description":"<b>Silent</b> classic"}]}}""")
            s.json("/metadata/nosferatu1922", """{"metadata":{"title":"Nosferatu","year":"1922"},"files":[
                {"name":"Nosferatu.ogv","format":"Ogg Video","size":"1000","source":"derivative"},
                {"name":"Nosferatu_512kb.mp4","format":"512Kb MPEG4","size":"2000","source":"derivative","length":"5634.2","height":"480"},
                {"name":"Nosferatu.srt","format":"SubRip"},
                {"name":"__ia_thumb.jpg","format":"Item Tile"}]}""")
            val plugin = InternetArchivePlugin(s.http(), s.base)
            val streams = plugin.streams(PlayTarget.Film(WatchKey.tmdbFilm(653), ExternalIds(tmdb = 653), "Nosferatu", 1922))
            assertEquals("${s.base}/download/nosferatu1922/Nosferatu_512kb.mp4", streams[0].url)
            assertTrue(streams.all { it.isPublic })
            assertEquals(5_634_200L, streams[0].durationMs)
            assertEquals(1, streams[0].subtitles.size)
            val rows = plugin.catalogPage(plugin.catalogs().first())
            assertEquals(WatchKey("m:ia:nosferatu1922"), rows.single().key)
            assertEquals("Silent classic", rows.single().overview)
            assertEquals(emptyList(), plugin.streams(PlayTarget.Film(WatchKey.tmdbFilm(1), ExternalIds(), "New Film", 2023)))
        }
    }

    @Test fun parsesFileNames() {
        assertEquals(ParsedName("Harbour Town", null, 2, 4), ParsedName.of("Harbour.Town.S02E04.1080p.WEB-DL.mkv"))
        assertEquals(ParsedName("Ember Road", 2021, null, null), ParsedName.of("Ember Road (2021).mp4"))
        assertEquals(ParsedName("The Matrix", 1999, null, null), ParsedName.of("The.Matrix.1999.2160p.UHD.mkv"))
        assertEquals(ParsedName("Show", null, 1, 12), ParsedName.of("Show - 1x12.mkv"))
        assertEquals(TitleType.FILM, TitleType.valueOf("FILM"))
    }
}
