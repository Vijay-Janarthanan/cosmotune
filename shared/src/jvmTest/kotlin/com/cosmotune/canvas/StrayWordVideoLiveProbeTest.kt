package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class StrayWordVideoLiveProbeTest {
    @Test fun probe() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_STRAY_WORD_LIVE") == "1")
        val title = "En Iniya Pon Nilave Pon"
        val artist = "K.J.Yesudas"
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
        for (query in listOf("\"$title\" video song", "$artist $title official music video", "\"$title\" $artist official video")) {
            Thread.sleep(1500)
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            val body = http.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { it.body?.string().orEmpty() }
            val results = MusicVideoSearch.parseAnswer(body).orEmpty()
            println("PROBE query '$query': ${results.size} results")
            results.take(12).forEach { c ->
                println("PROBE   ${c.videoId} \"${c.title}\" by ${c.channelTitle} (${c.durationSec}s, artist=${c.isOfficialMusicVideo}, verified=${c.isVerifiedChannel}) -> " +
                    (VideoMatcher.rejection(c, title, artist, 0L, null) ?: "eligible") + " / namesSong=${VideoMatcher.namesSong(c, title)}")
            }
        }
        val outcome = MusicVideoSearch(OkHttpClient(), { println("PROBE LOG: $it") }).findOutcome(title, artist, 0L)
        println("PROBE outcome: ${outcome.candidates.map { it.videoId }} asked ${outcome.asked} unanswered ${outcome.unanswered}")
    }
}
