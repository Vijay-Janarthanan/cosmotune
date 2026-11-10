package com.cosmotune.watch

import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import com.cosmotune.watch.plugins.stremio.BundledTorrentPlayback
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentPlaybackTest {
    private val hash = "a".repeat(40)

    @Test fun installedAddonsKeepValidTorrentOffersWhenTheBuiltInEngineIsEnabled() = runBlocking {
        TestServer().use { server ->
            server.json("/manifest.json", """{"id":"test","name":"Test","resources":["stream"],"types":["movie"],"idPrefixes":["tt"]}""")
            server.json("/stream/movie/tt37287335.json", """{"streams":[
                {"infoHash":"$hash","fileIdx":0,"behaviorHints":{"proxyHeaders":{"request":{"Authorization":"irrelevant"}}}},
                {"infoHash":"$hash"},
                {"infoHash":"not-an-infohash"}]}""")
            var enabled = false
            val plugin = StremioPlugin("test", "Test", "TEST", StremioClient(server.http(), server.url("/manifest.json")),
                setOf(Capability.STREAMS), torrentsEnabled = { enabled })
            val target = PlayTarget.Film(WatchKey.film("imdb", "tt37287335"), ExternalIds(imdb = "tt37287335"), "Obsession", 2026)
            assertEquals(BundledTorrentPlayback.DISABLED, assertFailsWith<IllegalStateException> { plugin.streams(target) }.message)
            enabled = true
            val offers = StreamRanker().rank(plugin.streams(target))
            assertEquals(2, offers.size)
            assertTrue(offers.all { it.isTorrent && it.headers.isEmpty() && !it.isPublic })
            assertEquals(setOf(0, null), offers.map { it.torrentFileIndex }.toSet())
            val engine = BundledTorrentPlayback { null }
            assertEquals("The torrent engine is missing from this Cosmotune build.",
                assertFailsWith<IllegalStateException> { engine.open(offers.first()) }.message)
        }
    }
}
