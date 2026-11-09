package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugins.iptv.GuideCache
import com.cosmotune.watch.plugins.iptv.IptvOrgPlugin
import com.cosmotune.watch.plugins.iptv.M3uParser
import com.cosmotune.watch.plugins.iptv.PlaylistPlugin
import com.cosmotune.watch.plugins.iptv.XmltvParser
import com.cosmotune.watch.plugins.iptv.XtreamPlugin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvParsersTest {
    private val playlist = """
        #EXTM3U url-tvg="http://guide.example/epg.xml.gz"
        #EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="BBC One" tvg-logo="https://logo/bbc.png" tvg-chno="101" group-title="News, UK",BBC One HD
        #EXTVLCOPT:http-user-agent=TestAgent/1.0
        #EXTVLCOPT:http-referrer=https://ref.example/
        https://stream.example/bbc/index.m3u8
        #EXTINF:-1 tvg-id="local-7" group-title="Movies" catchup="shift" catchup-days="3",Film Channel
        http://plain.example/film.ts|User-Agent=Piped%20Agent
        #EXTINF:-1,No Attributes
        https://x.example/a.m3u8
        #EXTINF:-1 tvg-id="Magnet.uk",Not a stream
        magnet:?xt=urn:btih:abc
    """.trimIndent()

    @Test fun parsesEntriesOptionsAndGuides() {
        val p = M3uParser.parse(playlist)
        assertEquals(listOf("http://guide.example/epg.xml.gz"), p.guideUrls)
        assertEquals(4, p.entries.size)
        val bbc = p.entries[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("BBCOne.uk", bbc.tvgId)
        assertEquals(101, bbc.number)
        assertEquals(listOf("News, UK"), bbc.groups)
        assertEquals("TestAgent/1.0", bbc.headers["User-Agent"])
        assertEquals("https://ref.example/", bbc.headers["Referer"])
        val film = p.entries[1]
        assertEquals("http://plain.example/film.ts", film.url)
        assertEquals("Piped Agent", film.headers["User-Agent"])
        assertEquals("shift", film.catchup?.mode)
        assertEquals(3, film.catchup?.days)
        assertEquals("No Attributes", p.entries[2].name)
        assertTrue(M3uParser.isIptvOrgId("BBCOne.uk"))
        assertTrue(M3uParser.isIptvOrgId("SunTV.in@HD"))
        assertFalse(M3uParser.isIptvOrgId("local-7"))
    }

    private val guide = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="BBCOne.uk"><display-name>BBC One</display-name><icon src="https://logo/bbc.png"/></channel>
          <programme start="20261106200000 +0000" stop="20261106210000 +0000" channel="BBCOne.uk">
            <title>Evening News</title><desc>The day's news.</desc><category>News</category>
          </programme>
          <programme start="20261106210000 +0000" stop="20261106220000 +0000" channel="BBCOne.uk"><title>Drama</title></programme>
          <programme start="20261108210000 +0000" stop="20261108220000 +0000" channel="BBCOne.uk"><title>Too late</title></programme>
          <programme start="20261107010000 +0530" stop="20261107020000 +0530" channel="Other.in"><title>Elsewhere</title></programme>
        </tv>"""

    @Test fun parsesGuideWindowAndOffsets() {
        val from = XmltvParser.time("20261106190000 +0000")!!
        val to = XmltvParser.time("20261107000000 +0000")!!
        val r = XmltvParser.parse(guide.byteInputStream(), from, to)
        assertEquals(listOf("BBCOne.uk"), r.channels.map { it.id })
        assertEquals(listOf("Evening News", "Drama", "Elsewhere"), r.programmes.map { it.title })
        assertEquals("The day's news.", r.programmes[0].description)
        assertEquals(XmltvParser.time("20261106193000 +0000"), r.programmes[2].startMs)
        val only = XmltvParser.parse(guide.byteInputStream(), from, to, wanted = setOf("Other.in"))
        assertEquals(listOf("Elsewhere"), only.programmes.map { it.title })
        assertNull(XmltvParser.time("garbage"))
    }

    @Test fun playlistPluginKeysChannelsAndReadsGzipGuide() = runBlocking<Unit> {
        TestServer().use { s ->
            s.text("/list.m3u", playlist.replace("http://guide.example/epg.xml.gz", s.url("/epg.xml.gz")))
            val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(guide.toByteArray()) } }.toByteArray()
            s.on("/epg.xml.gz") { MockResponse().setBody(Buffer().write(gz)) }
            val http = s.http()
            val now = XmltvParser.time("20261106203000 +0000")!!
            val plugin = PlaylistPlugin("m3u-1", "Mine", "M3U", PluginKind.M3U, http, { listOf(s.url("/list.m3u")) }, guideCache = GuideCache(http, { now }))
            val channels = plugin.channels()
            assertEquals(3, channels.size)
            val bbc = channels.first { it.name == "BBC One HD" }
            assertEquals(WatchKey("c:iptv:BBCOne.uk"), bbc.key)
            assertEquals(listOf("uk"), bbc.countries)
            assertTrue(channels.any { it.key == WatchKey("c:m3u-1:local-7") })
            val guideRows = plugin.guide(listOf(bbc), now - 3_600_000, now + 3_600_000 * 3)
            assertEquals(listOf("Evening News", "Drama"), guideRows.map { it.title })
            val streams = plugin.streams(PlayTarget.Live(bbc))
            assertEquals(1, streams.size)
            assertEquals(StreamProtocol.HLS, streams[0].protocol)
            assertEquals("TestAgent/1.0", streams[0].headers["User-Agent"])
            assertFalse(streams[0].isPublic, "a user's own playlist never travels in a room")
        }
    }

    @Test fun aDirectStreamCatalogueSkipsWebPagesAndEmbeddedGuides() = runBlocking<Unit> {
        TestServer().use { s ->
            s.text("/list.m3u", playlist.replace("http://guide.example/epg.xml.gz", s.url("/epg.xml.gz")) + "\n" + """
                #EXTINF:-1,Web Page
                https://www.youtube.com/@example/live
            """.trimIndent())
            val plugin = PlaylistPlugin("free-tv", "Free TV", "M3U", PluginKind.M3U, s.http(),
                { listOf(s.url("/list.m3u")) }, directStreamsOnly = true, includePlaylistGuides = false)
            val channels = plugin.channels()
            assertEquals(listOf("BBC One HD", "Film Channel", "No Attributes"), channels.map { it.name })
            assertEquals(WatchKey("c:iptv:BBCOne.uk"), channels.first().key)
            assertTrue(plugin.guide(channels, 0, Long.MAX_VALUE).isEmpty())
        }
    }

    @Test fun iptvOrgJoinsChannelsFeedsLogosAndStreams() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/api/channels.json", """[
                {"id":"SunTV.in","name":"Sun TV","country":"IN","categories":["entertainment"],"is_nsfw":false,"closed":null},
                {"id":"Adult.us","name":"Adult","country":"US","categories":["xxx"],"is_nsfw":true},
                {"id":"Gone.uk","name":"Gone","country":"UK","categories":[],"is_nsfw":false,"closed":"2020-01-01"}]""")
            s.json("/api/feeds.json", """[{"channel":"SunTV.in","id":"SD","languages":["tam"],"is_main":true}]""")
            s.json("/api/logos.json", """[{"channel":"SunTV.in","feed":null,"url":"https://logo/sun.png"}]""")
            s.json("/api/streams.json", """[
                {"channel":"SunTV.in","feed":"SD","title":"Sun TV","url":"https://cdn.example/sun.m3u8","referrer":null,"user_agent":null,"quality":"720p","label":"Not 24/7"},
                {"channel":"Adult.us","url":"https://cdn.example/x.m3u8"},
                {"channel":"Gone.uk","url":"https://cdn.example/gone.m3u8"},
                {"channel":null,"title":"Loose Channel","url":"http://loose.example/a.m3u8","user_agent":"UA"},
                {"channel":"SunTV.in","url":"magnet:?xt=1"}]""")
            val plugin = IptvOrgPlugin(s.http(), s.url("/api").removeSuffix("/"))
            val channels = plugin.channels()
            assertEquals(listOf("Loose Channel", "Sun TV"), channels.map { it.name })
            val sun = channels.first { it.name == "Sun TV" }
            assertEquals(WatchKey("c:iptv:SunTV.in"), sun.key)
            assertEquals(listOf("in"), sun.countries)
            assertEquals(listOf("tam"), sun.languages)
            assertEquals(listOf("Entertainment"), sun.categories)
            assertEquals("https://logo/sun.png", sun.logoUrl)
            val streams = plugin.streams(PlayTarget.Live(sun))
            assertEquals(1, streams.size)
            assertTrue(streams[0].isPublic)
            assertEquals(720, streams[0].height)
            val loose = plugin.streams(PlayTarget.Live(channels.first { it.name == "Loose Channel" }))
            assertFalse(loose[0].isPublic)
            assertEquals("UA", loose[0].headers["User-Agent"])
        }
    }

    @Test fun xtreamListsLiveAndBuildsAddresses() = runBlocking<Unit> {
        TestServer().use { s ->
            s.on("/player_api.php") { r ->
                val body = when (r.requestUrl?.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                    "get_live_streams" -> """[{"num":1,"name":"News One","stream_id":11,"stream_icon":"https://i/1.png","epg_channel_id":"NewsOne.us","category_id":"1","tv_archive":1,"tv_archive_duration":2},
                        {"num":2,"name":"Local","stream_id":12,"epg_channel_id":null,"category_id":"9"}]"""
                    "get_vod_streams" -> """[{"name":"EN - The Matrix (1999)","stream_id":55,"tmdb":"603","container_extension":"mkv"}]"""
                    else -> "[]"
                }
                MockResponse().setBody(body)
            }
            val plugin = XtreamPlugin("xt-1", "Family", s.http(), s.base, "user", "p@ss")
            assertNull(plugin.health())
            val channels = plugin.channels()
            assertEquals(WatchKey("c:iptv:NewsOne.us"), channels[0].key)
            assertEquals(listOf("News"), channels[0].categories)
            assertEquals("xc", channels[0].catchup?.mode)
            assertEquals(WatchKey("c:xt-1:12"), channels[1].key)
            val live = plugin.streams(PlayTarget.Live(channels[0]))
            assertEquals("${s.base}/live/user/p%40ss/11.m3u8", live.single().url)
            assertFalse(live.single().isPublic)
            val film = plugin.streams(PlayTarget.Film(WatchKey.tmdbFilm(603), com.cosmotune.watch.model.ExternalIds(tmdb = 603), "The Matrix", 1999))
            assertEquals("${s.base}/movie/user/p%40ss/55.mkv", film.single().url)
            assertEquals(com.cosmotune.watch.model.Container.MKV, film.single().container)
        }
    }
}
