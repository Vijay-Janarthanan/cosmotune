package com.cosmotune.video.signed

import com.cosmotune.music.data.innertube.InnerTubeXResolver
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.video.player.StartupTrace
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * The signed path's clients timed one by one (opt-in: VIDEO_LIVE=true), without an account: a session with no cookie, so the TV and
 * embedded clients are asked exactly as a signed request is but with no credential, the WEB client has no token to mint here, and the
 * rescue clients are what they always are. It shows what each attempt costs (the player request, the signature timestamp, the
 * cipher and throttling solves of every format) and which of them answer, in the order the walk tries them.
 */
class SignedResolverLiveTest {

    /** Solves every answer's links both ways, the batch the resolver now uses and one by one as it used to, and times each. */
    private class ComparingCipher : SignedCipher by InnerTubeXCipher {
        override suspend fun addresses(videoId: String, links: List<Pair<String?, String?>>): List<String?> {
            val batchBegan = System.nanoTime()
            val batch = InnerTubeXCipher.addresses(videoId, links)
            val batchMs = (System.nanoTime() - batchBegan) / 1_000_000
            val singleBegan = System.nanoTime()
            val single = links.map { (url, cipher) -> runCatching { InnerTubeXCipher.address(videoId, url, cipher) }.getOrNull() }
            val singleMs = (System.nanoTime() - singleBegan) / 1_000_000
            fun params(url: String?) = url?.substringAfter('?')?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }.orEmpty()
            val disagree = links.indices.count { params(batch[it]) != params(single[it]) }
            println("COMPARE $videoId ${links.size} links: batch ${batchMs}ms, one by one ${singleMs}ms, $disagree disagree")
            return batch
        }
    }

    private fun firstKilobyte(url: String, headers: Map<String, String>): Int {
        val request = okhttp3.Request.Builder().url("$url&range=0-1023").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return com.cosmotune.music.data.Http.client.newCall(request).execute().use { it.code }
    }

    private val videos = (System.getenv("VIDEO_STARTUP_IDS") ?: "dQw4w9WgXcQ,aqz-KE-bpKQ,M7lc1UVf-VE").split(',').map { it.trim() }

    @Test
    fun eachClientTimed() = runBlocking {
        assumeTrue(System.getenv("VIDEO_LIVE") == "true")
        StartupTrace.sink = { _, line -> println("TRACE $line") }
        // As the app starts it: a folder for the preprocessed players and a store for the remote player config (here in memory).
        val dir = java.nio.file.Files.createTempDirectory("cosmotune-signed").toFile()
        val store = object : InnerTubeXResolver.ConfigStore {
            val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
            override fun getString(key: String) = values[key] as? String
            override fun getLong(key: String) = values[key] as? Long
            override fun putString(key: String, value: String) { values[key] = value }
            override fun putLong(key: String, value: Long) { values[key] = value }
        }
        if (System.getenv("VIDEO_SIGNED_COLD") != "true") {
            InnerTubeXResolver.init(dir, store, null)
            kotlinx.coroutines.delay(15_000) // the warm-up the app runs 2 s after start
        }
        // VIDEO_SIGNED_COMPARE=true solves each answer's first links one by one as well, and says whether the batch agrees.
        val cipher = if (System.getenv("VIDEO_SIGNED_COMPARE") == "true") ComparingCipher() else InnerTubeXCipher
        val resolver = SignedPlayerResolver(YouTubeSession(), Innertube::signedScope, OkHttpSignedHttp, { _, _ -> null }, cipher,
            deadlineMs = 600_000)
        try {
            for (round in 1..2) for (id in videos) for (client in listOf(SignedPlayerClient.TV_SAMSUNG)) {
                val began = System.nanoTime()
                val result = runCatching { resolver.resolve(id, listOf(client)) }
                val ms = (System.nanoTime() - began) / 1_000_000
                val what = result.fold({ "answered, ${it.streams.videoOnly.size} video ${it.streams.audio.size} audio" }, { it.message.orEmpty() })
                // A solved link is only solved if googlevideo serves it: the first kilobyte of the largest picture and of the sound.
                val served = result.getOrNull()?.streams?.let { set ->
                    listOfNotNull(set.videoOnly.maxByOrNull { it.height }?.url, set.audio.firstOrNull()?.url).joinToString(" ") { url -> firstKilobyte(url, set.requestHeaders).toString() }
                }
                println("SIGNED round $round $id ${client.name}: ${ms}ms $what${served?.let { ", served $it" }.orEmpty()}")
            }
        } finally {
            StartupTrace.sink = null
        }
    }
}
