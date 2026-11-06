package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.store.StoredSource
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchProtection
import com.cosmotune.watch.store.WatchState
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchRepositoryTest {
    private val now = 1_762_461_600_000L

    private class Fake(override val id: String, val streamsOut: List<Stream> = emptyList(), val wait: Long = 0, val fail: Boolean = false,
        val channelsOut: List<Channel> = emptyList(), val guideOut: List<EpgProgramme> = emptyList()) : WatchPlugin {
        override val kind = PluginKind.PLUTO_TV
        override val displayName = id
        override val badge = id.uppercase()
        override val capabilities = setOf(Capability.STREAMS, Capability.LIVE, Capability.EPG)
        var asked = 0
        override suspend fun streams(target: PlayTarget): List<Stream> { asked++; delay(wait); if (fail) error("boom"); return streamsOut }
        override suspend fun channels() = channelsOut
        override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long) = guideOut
    }

    private fun stream(plugin: String, label: String, height: Int?, filename: String? = null) =
        Stream(StreamSource(plugin, plugin, plugin), label, url = "https://$plugin.example/$label", protocol = StreamProtocol.HLS, height = height, filename = filename)

    private fun dir() = Files.createTempDirectory("watch-test").toFile().also { it.deleteOnExit() }

    private fun repo(plugins: List<WatchPlugin>, dir: File = dir(), protection: WatchProtection = UnsealedWatchFiles) =
        DefaultWatchRepository(WatchConfig(OkHttpClient(), dir, null, tmdbKey = null, now = { now }, timeoutMs = 2_000),
            WatchStore(File(dir, "watch.json"), protection), builtIns = { _, _ -> plugins })

    private val film = PlayTarget.Film(WatchKey.tmdbFilm(603), ExternalIds(tmdb = 603, imdb = "tt0133093"), "The Matrix", 1999, 136)

    @Test fun builtInsHaveTheAgreedDefaults() {
        val defaults = BuiltInPlugins.all.associate { it.id to it.defaultEnabled }
        assertEquals(mapOf("iptv" to true, "pluto" to true, "samsung" to true, "dd" to true, "plex" to true, "ia" to true, "youtube" to true,
            "pdm" to false, "opensubtitles" to true, "watchhub" to false), defaults)
    }

    @Test fun sourcesFillInBestFirstAndSkipDisabledAndFailingPlugins() = runBlocking<Unit> {
        val pluto = Fake("pluto", listOf(stream("pluto", "720p", 720)), wait = 50)
        val plex = Fake("plex", listOf(stream("plex", "1080p", 1080)))
        val iptv = Fake("iptv", fail = true)
        val pdm = Fake("pdm", listOf(stream("pdm", "4k", 2160)))
        val r = repo(listOf(pluto, plex, iptv, pdm))
        val states = r.sources(film).toList()
        assertEquals(setOf("pluto", "plex", "iptv"), states.first().pending, "Public Domain Movies is off by default and is not asked")
        assertEquals(0, pdm.asked)
        val last = states.last()
        assertTrue(last.done)
        assertEquals(listOf("plex", "pluto"), last.streams.map { it.source.pluginId })
        assertEquals("boom", last.errors["iptv"])
        assertTrue(states.size >= 3, "the list fills in as plugins answer")
        val hinted = r.sourcesNow(film, StreamHint(filename = "m.mkv"))
        assertEquals("plex", hinted.best?.source?.pluginId)
        val withHint = repo(listOf(Fake("plex", listOf(stream("plex", "1080p", 1080), stream("plex", "720p", 720, "m.mkv"))))).sourcesNow(film, StreamHint(filename = "m.mkv"))
        assertEquals("720p", withHint.best?.label, "the room's or the last release wins")
        r.setPluginEnabled("plex", false)
        assertEquals(listOf("pluto"), r.sourcesNow(film).streams.map { it.source.pluginId })
    }

    @Test fun switchesProgressMyListAndFavouritesPersistSealed() = runBlocking<Unit> {
        val dir = dir()
        val sealing = object : WatchProtection {
            override fun seal(bytes: ByteArray) = bytes.reversedArray()
            override fun open(bytes: ByteArray) = bytes.reversedArray()
        }
        val r = repo(listOf(Fake("pluto")), dir, sealing)
        r.setPluginEnabled("pdm", true)
        r.setPluginEnabled("pluto", false)
        val summary = TitleSummary(WatchKey.tmdbSeries(1399), TitleType.SERIES, "Game of Thrones")
        r.saveProgress(WatchProgress(WatchKey.tmdbEpisode(1399, 1, 2), 600_000, 3_600_000, now, summary, "The Kingsroad"))
        r.saveProgress(WatchProgress(WatchKey.tmdbEpisode(1399, 1, 1), 3_500_000, 3_600_000, now - 1000, summary))
        r.saveProgress(WatchProgress(WatchKey.channel("iptv", "X.uk"), 1, 1, now))
        r.setInMyList(summary, true)
        r.setFavouriteChannel(WatchKey.channel("iptv", "BBCOne.uk"), true)
        val raw = File(dir, "watch.json").readText()
        assertFalse(raw.contains("Game of Thrones"), "the file is sealed at rest")
        val again = repo(listOf(Fake("pluto")), dir, sealing)
        val infos = again.plugins.value.associateBy { it.id }
        assertTrue(infos.getValue("pdm").enabled)
        assertFalse(infos.getValue("pluto").enabled)
        assertTrue(infos.getValue("opensubtitles").enabled)
        assertEquals(listOf(WatchKey.tmdbEpisode(1399, 1, 2)), again.continueWatching.value.map { it.key }, "one card per series, channels never")
        assertEquals("S1 E2", again.continueWatching.value.single().episodeLabel)
        assertTrue(again.isInMyList(summary.key))
        assertEquals(setOf(WatchKey.channel("iptv", "BBCOne.uk")), again.favouriteChannels.value)
    }

    @Test fun channelsMergeAcrossPluginsWithFacetsAndGuide() = runBlocking<Unit> {
        val bbc = Channel(WatchKey.channel("iptv", "BBCOne.uk"), "iptv", "BBC One", countries = listOf("uk"), languages = listOf("eng"), categories = listOf("News"), epgId = "BBCOne.uk")
        val bbcPluto = bbc.copy(pluginId = "pluto", epgId = "p-bbc", categories = listOf("Entertainment"))
        val sun = Channel(WatchKey.channel("iptv", "SunTV.in"), "iptv", "Sun TV", countries = listOf("in"), languages = listOf("tam"), categories = listOf("Entertainment"), epgId = "SunTV.in")
        val prog = EpgProgramme("p-bbc", now - 600_000, now + 600_000, "News at Nine")
        val iptv = Fake("iptv", channelsOut = listOf(bbc, sun))
        val pluto = Fake("pluto", channelsOut = listOf(bbcPluto), guideOut = listOf(prog))
        val r = repo(listOf(iptv, pluto))
        val all = r.channels()
        assertEquals(2, all.size)
        assertEquals(listOf("News", "Entertainment"), all.first { it.name == "BBC One" }.categories)
        assertEquals(listOf("Sun TV"), r.channels(ChannelFilter(country = "in")).map { it.name })
        assertEquals(listOf("Sun TV"), r.channels(ChannelFilter(language = "ta")).map { it.name })
        val facets = r.channelFacets()
        assertEquals(setOf("uk", "in"), facets.countries.map { it.id }.toSet())
        assertEquals("India", facets.countries.first { it.id == "in" }.name)
        assertEquals("Tamil", facets.languages.first { it.id == "ta" }.name)
        val window = r.guide(all, now - 3_600_000, now + 3_600_000)
        assertEquals("News at Nine", window.rows.first { it.channel.name == "BBC One" }.programmes.single().title)
        assertEquals("News at Nine", r.nowNext(all.first { it.name == "BBC One" }).now?.title)
        r.setFavouriteChannel(sun.key, true)
        assertEquals("Sun TV", r.channels().first().name, "favourites first")
    }

    @Test fun addOnsInstallFromLinksAndTravelInPacks() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/good/manifest.json", """{"id":"org.good","name":"Good add-on","description":"Free films","resources":["stream"],"types":["movie"]}""")
            s.json("/p2p/manifest.json", """{"id":"org.p2p","name":"P2P","resources":["stream"],"types":["movie"],"behaviorHints":{"p2p":true}}""")
            s.json("/conf/manifest.json", """{"id":"org.c","name":"Conf","resources":["stream"],"types":["movie"],"behaviorHints":{"configurationRequired":true}}""")
            val r = repo(emptyList())
            val good = s.url("/good/manifest.json")
            val preview = r.previewInstallLink(good.replace("http://", "stremio://"))
            // stremio:// becomes https, which the loopback server does not speak; the http form is what the test can reach.
            assertTrue(preview.manifestUrl.startsWith("https://"))
            val ok = r.previewInstallLink(good)
            assertEquals("Good add-on", ok.name)
            assertEquals(listOf("Films"), ok.offers)
            assertTrue(ok.installable)
            assertIs<AddSourceResult.Added>(r.installFromLink(good))
            assertTrue(r.previewInstallLink(good).alreadyInstalled)
            assertEquals("Peer-to-peer add-ons are not supported", (r.installFromLink(s.url("/p2p/manifest.json")) as AddSourceResult.Failed).message)
            assertIs<AddSourceResult.Failed>(r.installFromLink(s.url("/conf/manifest.json")))
            assertIs<AddSourceResult.Failed>(r.installFromLink(good), "no duplicates")
            val info = r.plugins.value.last()
            assertEquals("Good add-on", info.name)
            assertFalse(info.builtIn)
            // Packs carry https manifest URLs only; this loopback add-on is http, so a pack of it is empty.
            assertEquals(emptyList(), PluginPack.decode(r.exportPluginPack()))
            r.removeUserSource(info.id)
            assertTrue(r.plugins.value.none { it.id == info.id })
        }
    }

    @Test fun packsKeepOnlyShareableManifestUrls() {
        val link = PluginPack.encode(listOf(
            PluginPack.Item("https://addon.example/manifest.json", "Plain"),
            PluginPack.Item("https://addon.example/eyJkZWJyaWQiOiJzZWNyZXQifQ/manifest.json", "Configured with a token"),
            PluginPack.Item("https://addon.example/manifest.json?key=abc", "Query"),
            PluginPack.Item("http://addon.example/manifest.json", "Plain http"),
            PluginPack.Item("https://other.example/v1/lang/manifest.json", "Short path"),
        ))
        assertTrue(link.startsWith("cosmotune://watch/pack?d="))
        assertTrue(link.length < 300)
        assertEquals(listOf("Plain", "Short path"), PluginPack.decode(link).map { it.n })
    }

    @Test fun watchPluginsSyncCarriesAddOnUrlsAndSwitchesOnly() {
        val state = WatchState(sources = listOf(
            StoredSource("st-1", UserSourceSpec.StremioAddon("https://a.example/manifest.json"), enabled = false, addedAtMs = 1, name = "A"),
            StoredSource("jf-1", UserSourceSpec.Jellyfin("http://nas:8096", "me", ""), addedAtMs = 1, name = "Home"),
        ))
        val snapshot = SyncWatchPlugins.snapshot(state)
        assertEquals(setOf("st-1"), snapshot.keys, "servers and their sign-ins never sync")
        assertEquals("""{"url":"https://a.example/manifest.json","name":"A","enabled":false}""", snapshot.getValue("st-1").toString())
        val other = WatchState()
        val applied = SyncWatchPlugins.apply(other, snapshot + ("st-bad" to kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive("javascript:alert(1)")) }), emptySet(), 5)
        assertEquals(listOf("st-1"), applied.sources.map { it.id })
        assertFalse(applied.sources.single().enabled)
        val removed = SyncWatchPlugins.apply(applied, emptyMap(), setOf("st-1"), 6)
        assertTrue(removed.sources.isEmpty())
        val kept = SyncWatchPlugins.apply(state, emptyMap(), setOf("jf-1"), 6)
        assertEquals(2, kept.sources.size, "a sync never removes a server")
        assertNotNull(kept)
    }

    @Test fun fakeRepositoryServesTheBoard() = runBlocking<Unit> {
        val fake = FakeWatchRepository()
        val home = fake.home()
        assertEquals("The Long Night Shift", home.featured?.summary?.name)
        assertEquals(3, home.continueWatching.size)
        assertTrue(home.liveNow.all { it.now != null })
        val sources = fake.sourcesNow(fake.targetOf(home.rows.first().items.first()))
        assertEquals(3, sources.streams.size)
        assertEquals("Resume S2 E4", fake.nextUp(WatchKey.tmdbSeries(101))?.label)
    }
}

class WatchRoomTest {
    private val summary = TitleSummary(WatchKey.tmdbSeries(1399), TitleType.SERIES, "Game of Thrones", 2011, posterUrl = "https://img/p.jpg", ids = ExternalIds(tmdb = 1399, imdb = "tt0944947"))

    @Test fun aRoomItemCarriesTheKeyIdsAndReleaseButNoPrivateAddress() = runBlocking<Unit> {
        val target = PlayTarget.EpisodeOf(WatchKey.tmdbEpisode(1399, 1, 2), summary.ids, "Game of Thrones", 1, 2, episodeName = "The Kingsroad", year = 2011)
        val server = Stream(StreamSource("jf-abc12", "Home", "JF"), "Direct play", url = "http://192.168.1.5:8096/Videos/x/stream", protocol = StreamProtocol.PROGRESSIVE,
            headers = mapOf("X-Emby-Token" to "secret"), sizeBytes = 2_254_857_830, filename = "GoT.S01E02.mkv", durationMs = 3_360_000, bingeGroup = "jf-1080")
        val item = WatchRoom.trackOf(target, summary, server)
        assertEquals("e:tmdb:1399:1:2", item.videoId)
        assertEquals(com.cosmotune.music.data.listentogether.PartyTrack.KIND_WATCH, item.kind)
        assertEquals("S1 · E2 · The Kingsroad", item.artist)
        assertEquals(null, item.url, "a server's address never travels")
        assertEquals("jellyfin", item.watch?.plugin)
        assertEquals("GoT.S01E02.mkv", item.watch?.name)
        assertEquals("tt0944947", item.watch?.imdb)
        assertFalse(item.toString().contains("secret") || item.toString().contains("192.168"))
        val public = Stream(StreamSource("ia", "Internet Archive", "ARC"), "MP4", url = "https://archive.org/download/x/x.mp4", protocol = StreamProtocol.PROGRESSIVE, isPublic = true)
        assertEquals("https://archive.org/download/x/x.mp4", WatchRoom.trackOf(PlayTarget.Film(WatchKey("m:ia:x"), ExternalIds(), "X"), null, public).url)
        val tokened = public.copy(url = "https://cdn.example/live.m3u8?jwt=abc")
        assertEquals(null, WatchRoom.trackOf(PlayTarget.Film(WatchKey("m:ia:x"), ExternalIds(), "X"), null, tokened).url)

        // A member turns it back into what to ask their own plugins for, with the host's release as the hint.
        val back = WatchRoom.targetOf(item, FakeWatchRepository()) as PlayTarget.EpisodeOf
        assertEquals(1, back.season); assertEquals(2, back.episode)
        assertEquals("tt0944947", back.seriesIds.imdb)
        assertEquals("GoT.S01E02.mkv", WatchRoom.hintOf(item)?.filename)
        assertEquals(null, WatchRoom.fallbackOf(item))
        assertFalse(WatchRoom.canPlay(item.copy(videoId = "nonsense")))
    }

    @Test fun differentCutsAreNoted() {
        assertEquals(null, WatchRoom.lengthNote(6_720_000, 6_750_000))
        assertEquals("Your copy is 4 min longer: a different cut, so positions may not line up", WatchRoom.lengthNote(6_720_000, 6_960_000))
        assertEquals("Your copy is 2 min shorter: a different cut, so positions may not line up", WatchRoom.lengthNote(6_720_000, 6_600_000))
        assertEquals(null, WatchRoom.lengthNote(null, 6_600_000))
    }

    @Test fun aPackCanBeOfferedInTheRoomChat() {
        val link = PluginPack.encode(listOf(PluginPack.Item("https://addon.example/manifest.json", "Plain")))
        val message = WatchRoom.packOffer(link, "Ana")!!
        assertEquals(link, WatchRoom.packIn(message))
        assertEquals(null, WatchRoom.packIn("hello"))
        assertEquals(null, WatchRoom.packOffer("https://not-a-pack", "Ana"))
    }
}
