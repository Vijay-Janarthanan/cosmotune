package com.cosmotune.music.desktop

import com.cosmotune.podio.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class PodioPlaybackLiveTest {
    private fun decode(label: String, url: String) {
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(url, requested = DesktopPcmFormat(44_100, 2, 2)).getOrThrow()
            var samples = 0
            var nonzero = false
            while (samples < 44_100) {
                val block = decoder.readSamples() ?: break
                samples += block.size
                nonzero = nonzero || block.any { kotlin.math.abs(it) > 0.00001 }
            }
            assertTrue(samples > 1000 && nonzero, "$label decoded actual audio")
            println("PODIO AUDIO: $label decoded $samples samples; ${decoder.measuredFormat?.codec}")
        } finally { decoder.close() }
    }
    @Test fun radioPublisherRssAndSpotifyLinksMatchedToRssDecodeWithoutAccounts() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_PODIO_LIVE") == "1")
        val catalogue = PodioCatalogue()
        val stations = catalogue.stations("Groove Salad")
        val station = assertNotNull(stations.firstOrNull { it.url.startsWith("https:") && it.codec.equals("MP3", true) })
        decode("Radio Browser station", station.url)
        val feed = catalogue.feed("https://lexfridman.com/feed/podcast/")
        decode("Publisher RSS episode", feed.episodes.first().url)
        val spotify = catalogue.feed("https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL")
        assertTrue(spotify.episodes.isNotEmpty(), "public Spotify show exposes episodes")
        val item = spotify.episodes.first().item()
        assertTrue(!item.id.startsWith("podio:episode:spotify:"), "the playable identity belongs to publisher RSS")
        decode("Publisher episode matched from Spotify link", item.url)
    }
}
