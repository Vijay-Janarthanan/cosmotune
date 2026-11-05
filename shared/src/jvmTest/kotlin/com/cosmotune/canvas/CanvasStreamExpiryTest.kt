package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.StreamInfo
import com.opencanvas.core.stream.SyncOffset
import com.opencanvas.core.stream.SyncStore
import com.opencanvas.core.stream.isMediaStreamExpired
import com.opencanvas.core.stream.mediaStreamExpiryMs
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CanvasStreamExpiryTest {
    @Test fun explicitSignedExpiryHasADecoderOpeningMargin() {
        val url = "https://example.invalid/video?expire=1000&other=kept"
        assertEquals(1_000_000L, mediaStreamExpiryMs(url))
        assertFalse(isMediaStreamExpired(url, 939_999))
        assertTrue(isMediaStreamExpired(url, 940_000))
        assertTrue(isMediaStreamExpired(url, 1_000_001))
    }

    @Test fun unknownExpiryAndMalformedHintsDoNotInventAnExpiry() {
        for (url in listOf("file:///tmp/video.mp4", "https://example.invalid/video",
            "https://example.invalid/video?expire=not-a-time", "https://example.invalid/video?expire=-1",
            "https://example.invalid/video?expire=9223372036854775807")) {
            assertNull(mediaStreamExpiryMs(url))
            assertFalse(isMediaStreamExpired(url))
        }
    }

    @Test fun anExpiredCompletedSessionResolvesANewStreamWithoutLosingItsTimingMap() = runBlocking {
        val directory = Files.createTempDirectory("canvas-expiry-").toFile()
        val streams = AtomicInteger()
        val audioRequests = AtomicInteger()
        val nowSec = System.currentTimeMillis() / 1_000
        val backend = object : StreamBackend {
            override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution): StreamInfo {
                val call = streams.incrementAndGet()
                return StreamInfo("https://example.invalid/video?expire=${if (call == 1) nowSec - 1 else nowSec + 3600}",
                    durationMs = 200_000, width = 640, height = 360)
            }
            override suspend fun resolveAudio(videoId: String): com.opencanvas.core.stream.AudioStream? {
                audioRequests.incrementAndGet()
                return null
            }
        }
        try {
            SyncStore(directory).put("TRACK000001", "VIDEO000001", SyncOffset(123, .95), verifiedSelection = true)
            OpenCanvas.configure(OpenCanvasConfig(backend, instantBackend = null, cacheDirectory = directory, communityMapsUrl = null))
            suspend fun resolve() = withTimeout(5_000) {
                OpenCanvas.resolveFlow("Expiry fixture", "Artist", mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
                    trackVideoId = "TRACK000001").toList().last()
            }
            assertEquals(SyncStage.MEASURED, resolve().syncStage)
            val refreshed = resolve()
            assertFalse(isMediaStreamExpired(refreshed.videoStreamUrl))
            assertEquals(SyncStage.MEASURED, refreshed.syncStage)
            assertEquals(123L, refreshed.audioOffsetMs)
            assertEquals(2, streams.get(), "expired session must resolve another signed address")
            resolve()
            assertEquals(2, streams.get(), "the fresh completed session is still reused")
            assertEquals(0, audioRequests.get(), "refresh keeps the verified timing map")
        } finally {
            OpenCanvas.configure(OpenCanvasConfig(streamBackend = backend, instantBackend = null,
                cacheDirectory = null, communityMapsUrl = null))
            directory.deleteRecursively()
        }
    }
}
