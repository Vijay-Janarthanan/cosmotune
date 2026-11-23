package com.cosmotune.music.desktop

import com.cosmotune.podio.PodioLiveTitle
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real public stations played the way the desktop plays a Podio station (the app reads the stream, FFmpeg decodes it), for about
 * ten seconds each: an Icecast MP3 with song titles, an HE-AAC (AAC+) stream, and a station whose old address redirects.
 * It needs the internet, so it runs only with PODIO_LIVE=true.
 */
class DesktopLiveStationsLiveTest {
    private data class Station(val name: String, val url: String, val titles: Boolean)

    private val stations = listOf(
        Station("Icecast MP3 with titles (SomaFM Groove Salad)", "https://ice2.somafm.com/groovesalad-128-mp3", titles = true),
        Station("HE-AAC (SomaFM Groove Salad 64k AAC)", "https://ice2.somafm.com/groovesalad-64-aac", titles = true),
        Station("Old address that redirects (FIP)", "http://direct.fipradio.fr/live/fip-midfi.mp3", titles = false),
    )

    @Test fun realStationsPlayThroughTheAppsOwnReader() {
        assumeTrue(System.getenv("PODIO_LIVE") == "true")
        for (station in stations) play(station)
    }

    private fun play(station: Station) {
        val decoder = DesktopAudioDecoder()
        decoder.open(station.url, requested = DesktopPcmFormat(44_100, 2, 2), live = true).getOrThrow()
        try {
            assertTrue(decoder.readsItself, "${station.name} is read by the app, not by FFmpeg's own reader")
            val rate = decoder.outputFormat.sampleRate
            val started = System.currentTimeMillis()
            var bytes = 0L
            var peak = 0
            var frames = 0
            while (bytes < rate * 4L * 10 && System.currentTimeMillis() - started < 30_000) {
                val block = assertNotNull(decoder.read(), "${station.name} stopped after ${bytes / (rate * 4.0)} s")
                bytes += block.size; frames++
                for (i in 0 until block.size / 2 step 32) peak = maxOf(peak, abs(((block[2 * i + 1].toInt() shl 8) or (block[2 * i].toInt() and 0xFF)).toShort().toInt()))
            }
            val seconds = bytes / (rate * 4.0)
            val title = PodioLiveTitle.recent(station.url)
            println("${station.name}: ${decoder.measuredFormat} ${"%.1f".format(seconds)} s in $frames blocks, peak $peak, title '$title'")
            assertTrue(seconds >= 10, "${station.name} gave ${seconds}s in 30 s")
            assertTrue(peak > 500, "${station.name} is silent (peak $peak)")
            if (station.titles) assertNotNull(title, "${station.name} named no song")
        } finally { decoder.close() }
    }
}
