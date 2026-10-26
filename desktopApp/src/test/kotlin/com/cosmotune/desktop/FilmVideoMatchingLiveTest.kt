package com.cosmotune.desktop

import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

/** Explicit opt-in: public recordings for the three reported songs; no accounts or user settings. */
class FilmVideoMatchingLiveTest {
    @Test fun recentFilmSongsResolveWithoutTheReportedMontageOrArtwork() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_FILM_VIDEO_LIVE") == "1")
        OpenCanvasProvider.configure(null)
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = null, communityMapsUrl = null, log = { println("FILM VIDEO: $it") }))
        val songs = listOf(
            Triple("Nilaave Vaa (From \"Mouna Ragam\")", "9km1rgLG8bw", 299L),
            Triple("Andhi Mazhai Pozhikaruthu (From \"Raaja Paarvai\")", "jMZTiRcq0AI", 275L),
            Triple("Germaniyin Senthen Malare | Ullasa Paravaigal Movie Songs| Kamal Haasan | Rati", "rPp9eSV4H0M", 338L),
        )
        for ((name, id, seconds) in songs) {
            val resolved = OpenCanvasProvider.search(name, "S. P. Balasubrahmanyam, S. Janaki", null, id, "480p", seconds)
            // The available 5.1 remaster did not align with the stereo artwork recording.
            // Absence is valid here; selecting the reported poster/montage is a regression.
            if (id == "rPp9eSV4H0M" && resolved == null) {
                println("FILM VIDEO RESULT: $name -> no verified sync; retain the cover")
                continue
            }
            val clip = assertNotNull(resolved, "Synced film video for $name")
            println("FILM VIDEO RESULT: $name -> ${clip.videoId}")
            assertFalse(clip.videoId in setOf("jMZTiRcq0AI", "66yaoWv6Cuw", "rPp9eSV4H0M"), "The reported montage/artwork must not be selected")
        }
    }
}
