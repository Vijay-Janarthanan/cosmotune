package com.cosmotune.watch

import com.cosmotune.watch.LiveVideoSource.Companion.video
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.youtube.PrasarBharatiPlugin
import com.cosmotune.watch.plugins.youtube.YouTubeLivePlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveRankingTest {
    private val now = 1_762_461_600_000L

    private class Lists(override val id: String, val list: List<Channel>) : WatchPlugin {
        override val kind = PluginKind.IPTV_ORG
        override val displayName = id
        override val badge = id.uppercase()
        override val capabilities = setOf(Capability.LIVE)
        override suspend fun channels() = list
    }

    private fun repo(place: WatchPlace, source: LiveVideoSource, vararg extra: WatchPlugin): DefaultWatchRepository {
        val dir = Files.createTempDirectory("watch-rank").toFile().also { it.deleteOnExit() }
        return DefaultWatchRepository(WatchConfig(OkHttpClient(), dir, null, now = { now }, place = { place }, timeoutMs = 5_000),
            WatchStore(File(dir, "watch.json"), UnsealedWatchFiles),
            builtIns = { _, _ -> listOf(*extra, PrasarBharatiPlugin({ source }, { now }), YouTubeLivePlugin({ source }, { now })) })
    }

    private val sun = YouTubeLivePlugin.CHANNELS.first { it.slug == "SunNews" }
    private val aajTak = YouTubeLivePlugin.CHANNELS.first { it.slug == "AajTak" }
    private val ddNews = PrasarBharatiPlugin.CHANNELS.first { it.slug == "DDNews" }
    private val ddTamil = PrasarBharatiPlugin.CHANNELS.first { it.slug == "DDTamil" }

    @Test fun liveNowPutsTheViewersLanguageFirstAndSkipsYouTubeChannelsWithNothingLive() = runBlocking<Unit> {
        val source = LiveVideoSource()
        for (def in listOf(sun, aajTak, ddNews)) source.live[def.youTubeId] = listOf(video(def.slug.take(6).padEnd(11, 'x'), "${def.name} LIVE", def.youTubeId, live = true))
        val elsewhere = Channel(WatchKey.channel("iptv", "Elsewhere.us"), "iptv", "Elsewhere", categories = listOf("News"), countries = listOf("us"), languages = listOf("eng"))
        val r = repo(WatchPlace("in", "ta"), source, Lists("iptv", listOf(elsewhere)))
        val names = r.liveNow(6).map { it.channel.name }
        assertEquals("Sun News", names.first(), "a Tamil viewer in India sees Tamil news first")
        assertTrue("Polimer News" !in names && "DD Tamil" !in names, "nothing live on their YouTube now: not in Live now")
        assertTrue("Aaj Tak" in names, "Live now looks further down the list to fill the row")
        assertTrue(r.liveNow(6).first().now?.title == "Sun News LIVE")

        val tamil = r.channels(ChannelFilter(language = "tam")).map { it.name }
        assertTrue("Polimer News" in tamil && "DD Tamil" in tamil, "Live TV lists every channel, live now or not")
        assertTrue(tamil.indexOf("Polimer News") < tamil.indexOf("DD Tamil"), "an all-day channel before one that only goes live for programmes")
        val facets = r.channelFacets()
        assertEquals("ta", facets.languages.first().id)
        assertTrue(facets.languages.first().yours)
        assertEquals("in", facets.countries.first().id)
        assertTrue(r.search("sun news").channels.any { it.key == WatchKey("c:ytlive:SunNews") })
        r.close()
    }

    @Test fun aHindiViewerGetsHindiNewsFirst() = runBlocking<Unit> {
        val source = LiveVideoSource()
        for (def in listOf(sun, aajTak, ddTamil)) source.live[def.youTubeId] = listOf(video(def.slug.take(6).padEnd(11, 'y'), "${def.name} LIVE", def.youTubeId, live = true))
        val r = repo(WatchPlace("in", "hi"), source)
        assertEquals("Aaj Tak", r.liveNow(3).first().channel.name)
        r.close()
    }
}
