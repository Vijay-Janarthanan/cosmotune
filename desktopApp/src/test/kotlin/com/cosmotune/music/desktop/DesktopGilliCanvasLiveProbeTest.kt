package com.cosmotune.music.desktop

import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.opencanvas.core.stream.ProgressiveRangeSource
import com.opencanvas.core.sync.CanvasSyncPolicy
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

/** Opt-in anonymous-stream/native-decoder check; personal caches/settings are never modified. */
class DesktopGilliCanvasLiveProbeTest {
    @Test fun verifiedGilliVideosReachTheNativeDecoderAfterMetadataValidation() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_GILLI_CANVAS_LIVE") == "1")
        val root = File("../build/gilli-video-audit").canonicalFile.apply { mkdirs() }
        OpenCanvasProvider.configure(File(root, "cache"))
        val reports = mutableListOf<JsonObject>()
        val songs = listOf(
            Triple("Appadipodu", "-5Wbru5aN6A", "Vidyasagar, KK, Anuradha Sriram & Pa. Vijay"),
            Triple("Arjunar Villu", "g1Nk8NytV0M", "Vidyasagar, Sukhwinder Singh, Manikka Vinayagam & Kabilan"),
            Triple("Kokkorakko", "wVlghJLpWf0", "Vidyasagar, Udit Narayan, Sujatha & Yugabharathi"),
        )
        for ((title, id, artist) in songs) {
            val requested = "$title (From \"Gilli\")"
            val clip = withTimeout(35_000) {
                DesktopOpenCanvasProvider.search(requested, artist,
                    "Gilli (Original Motion Picture Soundtrack)", id, "480p")
            }
            assertNotNull(clip, title)
            assertTrue(clip.matches(requested.cleanedForCanvas(), artist, "Gilli (Original Motion Picture Soundtrack)"), title)
            val map = assertNotNull(clip.syncMap)
            val policy = CanvasSyncPolicy(map, clip.videoDurationMs)
            val frameChecks = mutableListOf<JsonObject>()
            ProgressiveRangeSource(clip.url, OkHttpClient(), clip.headers).use { source ->
                val decoder = DesktopCanvasDecoder()
                try {
                    decoder.open(source).getOrThrow()
                    val pixels = ByteArray(decoder.width * decoder.height * 4)
                    for (songMs in listOf(1_000L, 45_000L)) {
                        assertTrue(policy.isVisible(songMs), "$title has no picture at $songMs")
                        val target = policy.targetVideoMs(songMs)
                        val started = System.nanoTime()
                        assertTrue(decoder.seekToMs(target), "$title seek refused")
                        assertTrue(decoder.nextFrame(pixels, loop = false), "$title decoded no frame")
                        assertTrue(abs(decoder.lastPtsMs - target) < 500, "$title frame misses song position")
                        frameChecks += buildJsonObject {
                            put("songMs", songMs); put("videoTargetMs", target); put("framePtsMs", decoder.lastPtsMs)
                            put("decodeMs", (System.nanoTime() - started) / 1_000_000)
                            put("width", decoder.width); put("height", decoder.height)
                        }
                    }
                } finally { decoder.close() }
            }
            reports += buildJsonObject {
                put("song", title); put("videoId", clip.videoId); put("status", "DECODED_FRAMES")
                put("segments", map.segments.size); put("pictureAt90Seconds", policy.isVisible(90_000))
                put("frames", JsonArray(frameChecks))
            }
            File(root, "live-results.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(reports)))
        }
    }
}
