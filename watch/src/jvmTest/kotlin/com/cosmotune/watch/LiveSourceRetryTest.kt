package com.cosmotune.watch

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSourceRetryTest {
    /** A live source whose first [failures] channel lists end in a connection reset. */
    private class Flaky(override val id: String, private val listed: Channel, var failures: Int) : WatchPlugin {
        override val kind = PluginKind.IPTV_ORG
        override val displayName = id
        override val badge = "IPTV"
        override val capabilities = setOf(Capability.LIVE)
        override suspend fun channels(): List<Channel> {
            if (failures > 0) { failures--; throw IOException("Connection reset") }
            return listOf(listed)
        }
        override suspend fun streams(target: PlayTarget): List<Stream> = emptyList()
        override suspend fun health(): String? = runCatching { channels(); null }.getOrElse { it.message }
    }

    private fun channel(plugin: String, id: String, name: String) = Channel(WatchKey.channel(plugin, id), plugin, name)

    private fun <T> withRepository(clock: LongArray, vararg plugins: WatchPlugin, block: suspend (DefaultWatchRepository) -> T): T = runBlocking {
        val root = Files.createTempDirectory("watch-live-retry").toFile()
        try {
            val offline = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()
            val repository = DefaultWatchRepository(WatchConfig(offline, root, root.resolve("cache"), now = { clock[0] }),
                WatchStore(root.resolve("watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> plugins.toList() })
            block(repository)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun DefaultWatchRepository.failing(id: String): Boolean = plugins.value.firstOrNull { it.id == id }?.error == true

    private suspend fun DefaultWatchRepository.awaitFailing(id: String, failing: Boolean) =
        withTimeout(5_000) { while (failing(id) != failing) delay(10) }

    @Test fun channelsOfASourceThatResetComeBackOnTheNextSearchAfterAShortWhileNotHalfAnHour() {
        val clock = longArrayOf(1_000_000L)
        val flaky = Flaky(BuiltInPlugins.IPTV_ORG, channel("iptv", "Kovai.in", "Kovai Kalai"), failures = 1)
        val steady = Flaky("free", channel("free", "Nila.in", "Nila Tamil"), failures = 0)
        withRepository(clock, flaky, steady) { repository ->
            assertEquals(listOf("Nila Tamil"), repository.channels(ChannelFilter()).map { it.name }, "only the source that answered")
            repository.awaitFailing(BuiltInPlugins.IPTV_ORG, true)

            clock[0] += DefaultWatchRepository.MISSING_SOURCE_MS - 1
            assertEquals(1, repository.channels(ChannelFilter()).size, "kept for a short while, so a search does not wait on it each time")

            clock[0] += 1
            assertEquals(setOf("Nila Tamil", "Kovai Kalai"), repository.channels(ChannelFilter(query = "")).map { it.name }.toSet(),
                "asked again, the source that reset answers and its channels are back")
            repository.awaitFailing(BuiltInPlugins.IPTV_ORG, false)
        }
    }

    @Test fun tryAgainClearsTheErrorAndBringsTheChannelsBackAtOnce() {
        val clock = longArrayOf(1_000_000L)
        val flaky = Flaky(BuiltInPlugins.IPTV_ORG, channel("iptv", "Kovai.in", "Kovai Kalai"), failures = 1)
        val steady = Flaky("free", channel("free", "Nila.in", "Nila Tamil"), failures = 0)
        withRepository(clock, flaky, steady) { repository ->
            assertEquals(1, repository.channels(ChannelFilter()).size)
            repository.awaitFailing(BuiltInPlugins.IPTV_ORG, true)
            repository.retryPlugin(BuiltInPlugins.IPTV_ORG)
            assertFalse(repository.failing(BuiltInPlugins.IPTV_ORG), "the source answered Try again")
            assertTrue(repository.channels(ChannelFilter()).any { it.name == "Kovai Kalai" }, "no wait for the short while to pass")
        }
    }

    @Test fun aListCutOffHalfwayIsAskedOnceMoreAndThenTheOldCopyIsRead() = runBlocking {
        val dir = Files.createTempDirectory("watch-http-reset").toFile()
        try {
            var calls = 0
            var resetEvery = true
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                calls++
                // The first answer is always cut off after a few bytes; later ones too while [resetEvery].
                val body = if (resetEvery || calls == 1) resetBody("[1,2,") else "[1,2,3]".toResponseBody()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            }).build()
            // The saved copy is dated by the real clock, so the test clock starts there.
            val now = longArrayOf(System.currentTimeMillis())
            val http = WatchHttp(client, dir, now = { now[0] })
            val read: (java.io.InputStream) -> String = { it.readBytes().decodeToString() }

            resetEvery = false
            assertEquals("[1,2,3]", http.stream("https://lists.example/channels.json", ttlMs = 1_000, read = read), "the reset first try is asked once more")
            assertEquals(2, calls)

            now[0] += 10_000
            resetEvery = true
            assertEquals("[1,2,3]", http.stream("https://lists.example/channels.json", ttlMs = 1_000, read = read), "both tries reset: last time's copy")
            assertEquals(4, calls)
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") }, "no half-written list is left behind")
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A body that gives [start] and then fails the way a dropped connection does. */
    private fun resetBody(start: String): okhttp3.ResponseBody = object : okhttp3.ResponseBody() {
        private val cut = object : okio.ForwardingSource(okio.Buffer().writeUtf8(start)) {
            override fun read(sink: okio.Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it == -1L) throw IOException("Connection reset") }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = cut
    }
}
