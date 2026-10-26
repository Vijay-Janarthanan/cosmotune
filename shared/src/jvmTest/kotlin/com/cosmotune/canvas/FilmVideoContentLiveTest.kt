package com.cosmotune.canvas

import com.opencanvas.core.stream.*
import com.opencanvas.core.sync.Mp4FrameSizes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull

class FilmVideoContentLiveTest {
    @Test fun inspectReportedArtworkAndPublishedFilmVideoHeaders() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_FILM_HEADER_LIVE") == "1")
        val backend = InnerTubeBackend.android()
        for (id in listOf("rPp9eSV4H0M", "GiUoiyF2Rms", "U7twY4U73Bw")) {
            val stream = assertNotNull(backend.resolveAudio(id))
            val frames = assertNotNull(Mp4FrameSizes.read(StreamReader(AudioFetcher(OkHttpClient()), stream)))
            println("FILM CONTENT: $id kbps=${frames.videoKbps} motion=${frames.videoMotion} keyIrregularity=${frames.keyIrregularity}")
        }
    }
}
