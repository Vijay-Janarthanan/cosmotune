package com.cosmotune.music.desktop

import com.cosmotune.podio.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Opt-in public discovery + the actual native Podio audio route; no installed provider profile. */
class PodioYouTubePlaybackLiveTest {
    @Test(timeout = 180_000) fun publicYouTubePodcastHasRealAudioAndKeepsItsSpokenIdentity() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_PODIO_YOUTUBE_LIVE") == "1")
        val adapter = YouTubePodcasts()
        val shows = adapter.search("Lex Fridman podcast")
        val show = assertNotNull(shows.firstOrNull { it.title.contains("Lex Fridman", true) })
        val showId = assertNotNull(YouTubePodcasts.showId(show.feed))
        val sharedLink = if (showId.startsWith("MPSPPL")) "https://music.youtube.com/playlist?list=${showId.removePrefix("MPSP")}" else show.feed
        val feed = adapter.feed(sharedLink, show)
        val episode = assertNotNull(feed.episodes.firstOrNull())
        val song = episode.item().song()
        assertTrue(song.isPodio)
        assertNull(song.localUri)
        val live = withTimeout(90_000) { DesktopSourceRegistry.resolveLive(song, "HIGH").getOrThrow() }
        assertEquals("podio:youtube", live.stream.sourceId)
        assertNull(live.pendingSubstitute, "Podcasts do not race music providers for another recording")
        val decoder = DesktopAudioDecoder()
        var samples = 0
        var nonzero = false
        try {
            decoder.open(live.stream.url, live.stream.headers, DesktopPcmFormat(44_100, 2, 4, true),
                windowed = live.stream.windowedReads).getOrThrow()
            while (samples < 44_100) {
                val block = decoder.readSamples() ?: break
                samples += block.size
                nonzero = nonzero || block.any { kotlin.math.abs(it) > 0.00001 }
            }
            assertTrue(samples > 1_000 && nonzero, "The podcast must decode real audio")
            val report = buildJsonObject {
                put("show", show.title); put("episode", episode.title); put("id", song.videoId)
                put("samples", samples); put("codec", decoder.measuredFormat?.codec)
                put("source", live.stream.sourceId); put("status", "DECODED_AUDIO")
            }
            File("../build/podio-youtube-live.json").writeText(report.toString())
            println("PODIO YOUTUBE: decoded $samples samples; ${decoder.measuredFormat?.codec}; no music-provider upgrade")
        } finally { decoder.close() }
    }
}
