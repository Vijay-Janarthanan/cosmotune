package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelMergeTest {
    private fun channel(plugin: String, id: String, name: String, countries: List<String> = emptyList()) =
        Channel(WatchKey.channel(plugin, id), plugin, name, countries = countries)

    @Test fun theSameChannelFromTwoSourcesIsOneAndAChannelOfAnotherNameOrCountryIsNot() {
        val merged = ChannelMerge.byName(listOf(
            channel("iptv", "SunTV.in", "Sun TV", listOf("in")), channel("st-cnc", "cnc:sun", "SUN TV"),
            channel("iptv", "SunTVHD.in", "Sun TV HD", listOf("in")), channel("other", "sun.lk", "Sun TV", listOf("lk")),
        ))
        assertEquals(3, merged.size, "four listings, three channels")
        val india = merged.single { it.key.value == "c:iptv:SunTV.in" }
        assertEquals(listOf("st-cnc"), india.also.map { it.pluginId }, "the add-on's copy is the same channel")
        assertTrue(merged.single { it.name == "Sun TV HD" }.also.isEmpty(), "HD is a channel of its own")
        assertTrue(merged.single { "lk" in it.countries }.also.isEmpty(), "a channel of another country is not the same one")
        assertEquals("st-cnc", india.asListedBy("st-cnc")?.pluginId)
    }

    /** A live source that lists one channel and plays only its own. */
    private class Live(override val id: String, private val listed: Channel) : WatchPlugin {
        override val kind = PluginKind.STREMIO_ADDON
        override val displayName = id
        override val badge = id.uppercase()
        override val capabilities = setOf(Capability.LIVE)
        override suspend fun channels() = listOf(listed)
        override suspend fun streams(target: PlayTarget): List<Stream> {
            val live = target as? PlayTarget.Live ?: return emptyList()
            if (live.channel.pluginId != id || live.channel.key != listed.key) return emptyList()
            return listOf(Stream(StreamSource(id, id, badge), "$id copy", url = "https://$id.example/live.m3u8", protocol = StreamProtocol.HLS, isLive = true))
        }
    }

    @Test fun oneChannelListedByTwoSourcesIsOneRowAndPlaysFromEither() = runBlocking {
        val root = Files.createTempDirectory("watch-channels").toFile()
        try {
            val offline = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()
            val a = Live("free", channel("free", "SunTV.in", "Sun TV", listOf("in")))
            val b = Live("addon", channel("addon", "cnc:sun", "SUN TV"))
            val repository = DefaultWatchRepository(WatchConfig(offline, root, root.resolve("cache")),
                WatchStore(root.resolve("watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> listOf(a, b) })
            val found = repository.channels(ChannelFilter(query = "sun tv")).filter { it.name.equals("Sun TV", true) }
            assertEquals(1, found.size, "one Sun TV, not one per source")
            val sources = repository.sourcesNow(PlayTarget.Live(found.single()), null)
            assertEquals(setOf("free copy", "addon copy"), sources.streams.map { it.label }.toSet(), "each source gives its own copy")
        } finally {
            root.deleteRecursively()
        }
    }
}
