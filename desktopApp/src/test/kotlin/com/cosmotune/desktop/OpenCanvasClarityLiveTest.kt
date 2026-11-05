package com.cosmotune.desktop

import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.cosmotune.music.desktop.DesktopCanvasDecoder
import com.opencanvas.core.stream.ProgressiveRangeSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCanvasClarityLiveTest {
    @Test fun requestedFullHdCanvasProducesFullHdFrames() = runBlocking {
        assumeTrue(System.getenv("VIDEO_LIVE") == "true")
        val clip = assertNotNull(OpenCanvasProvider.search("Never Gonna Give You Up", "Rick Astley", null,
            "dQw4w9WgXcQ", "1080p", 213))
        val source = ProgressiveRangeSource(clip.url, OkHttpClient(), clip.headers)
        val decoder = DesktopCanvasDecoder()
        try {
            decoder.open(source).getOrThrow()
            println("LIVE CANVAS: ${decoder.width}x${decoder.height}")
            assertTrue(decoder.height >= 1080, "the selected Full HD rendition reaches the decoder")
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            assertTrue(decoder.nextFrame(pixels, loop = false))
            for (target in listOf(30_000L, 90_000L, 150_000L)) {
                assertTrue(decoder.seekToMs(target))
                assertTrue(decoder.nextFrame(pixels, loop = false), "a frame after seeking to $target")
                assertTrue(kotlin.math.abs(decoder.lastPtsMs - target) < 1_000)
            }
        } finally {
            decoder.close()
            source.close()
        }
    }
}
