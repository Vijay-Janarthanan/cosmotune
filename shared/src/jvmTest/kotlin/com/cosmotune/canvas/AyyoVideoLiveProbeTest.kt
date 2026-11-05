package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.SyncStage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class AyyoVideoLiveProbeTest {
    @Test fun thePublishedThinkTapesVideoCanAlignToThePlayingRecording() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_AYYO_VIDEO_LIVE") == "1")
        val root = File("../build/upstream-audit-2026-10-28").apply { mkdirs() }
        val messages = mutableListOf<String>()
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = File(root, "ayyo-video-cache"), communityMapsUrl = null,
            log = { line -> synchronized(messages) { messages += line } }))
        val result = withTimeout(100_000) {
            OpenCanvas.resolveFlow("Ayyo Ayyo", "Srikanth Deva, Udit Narayanan & Shalini Singh", 347,
                mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = "t9YyaNWhq8M", album = "M. Kumaran S O Mahalakshmi")
                .first { it.syncStage != SyncStage.PENDING }
        }
        File(root, "ayyo-video-result.json").writeText(buildJsonObject {
            put("videoId", result.videoId); put("stage", result.syncStage.name)
            put("confidence", result.syncConfidence); put("segments", result.syncSegments.size)
        }.toString())
        File(root, "ayyo-video.log").writeText(synchronized(messages) { messages.joinToString("\n") }
            .replace(Regex("https?://[^\\s\"'<>]+"), "<address>"))
        assertEquals(SyncStage.MEASURED, result.syncStage)
    }
}
