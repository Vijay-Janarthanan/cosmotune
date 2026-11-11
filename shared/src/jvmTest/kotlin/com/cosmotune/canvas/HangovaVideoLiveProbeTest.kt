package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Explicit opt-in (COSMOTUNE_HANGOVA_VIDEO_LIVE=1): the reported song, resolved once against the real YouTube with a
 * throwaway map folder; no account, no user profile. The official video is Sun TV's wc8XNJ2x1Xs (or its twin upload).
 */
class HangovaVideoLiveProbeTest {
    @Test fun hangovaFindsTheSunTvMusicVideo() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_HANGOVA_VIDEO_LIVE") == "1")
        val root = File("build/hangova-live").apply { deleteRecursively(); mkdirs() }
        val lines = mutableListOf<String>()
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = File(root, "maps"), communityMapsUrl = null,
            log = { line -> synchronized(lines) { lines += line }; println("HANGOVA: $line") }))
        val result = withTimeout(90_000) {
            OpenCanvas.resolveFlow("Hangova (From \"DC\")", "Anirudh Ravichander, Heisenberg", 248,
                mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = "kynWdUG1NvY", album = "DC")
                .first { it.syncStage != SyncStage.PENDING }
        }
        println("HANGOVA RESULT: ${result.videoId} ${result.syncStage} confidence ${result.syncConfidence} segments ${result.syncSegments.map { it.offsetMs }}")
        File(root, "hangova.log").writeText(synchronized(lines) { lines.joinToString("\n") }.replace(Regex("https?://[^\\s\"'<>]+"), "<address>"))
        assertEquals(SyncStage.MEASURED, result.syncStage)
        assertEquals(true, result.videoId in setOf("wc8XNJ2x1Xs", "8fYBnwVEdx8"), "Sun TV's music video, got ${result.videoId}")
    }
}
