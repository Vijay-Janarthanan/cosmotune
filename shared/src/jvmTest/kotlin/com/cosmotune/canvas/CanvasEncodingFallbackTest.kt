package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.AudioStream
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.StreamInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasEncodingFallbackTest {
    private fun box(name: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { a, b -> a + b }
        return number(payload.size + 8) + name.toByteArray() + payload
    }
    private fun number(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun track(id: Int, kind: String, scale: Int, sizes: IntArray, delta: Int): ByteArray {
        val table = ByteBuffer.allocate(sizes.size * 4).apply { sizes.forEach { putInt(it) } }.array()
        return box("trak", box("tkhd", number(0), number(0), number(0), number(id), ByteArray(68)),
            box("mdia", box("mdhd", number(0), number(0), number(0), number(scale), number(0), ByteArray(4)),
                box("hdlr", number(0), number(0), kind.toByteArray(), ByteArray(13)),
                box("minf", box("stbl", box("stts", number(0), number(1), number(sizes.size), number(delta)),
                    box("stsz", number(0), number(0), number(sizes.size), table)))))
    }
    private fun media(directory: File, name: String, audio: IntArray, moving: Boolean = true, audioOnly: Boolean = false): AudioStream {
        val rng = Random(71)
        val data = box("ftyp", "isom".toByteArray(), number(0), "isomiso2".toByteArray()) +
            box("moov", if (audioOnly) ByteArray(0) else track(1, "vide", 90_000,
                IntArray(4200) { if (moving) 4000 + rng.nextInt(12000) else if (it % 30 == 0) 30_000 else 6 }, 3000),
                track(2, "soun", 44_100, audio, 1024)) + box("mdat", ByteArray(2048))
        val file = File(directory, name).apply { writeBytes(data) }
        return AudioStream(file.toURI().toString(), durationMs = 140_000, contentLength = file.length())
    }
    private fun exercise(recover: Boolean, moving: Boolean = true, audioOnlyAlternative: Boolean = false) = runBlocking {
        val directory = Files.createTempDirectory("canvas-encoding-").toFile()
        val rng = Random(42)
        val audio = IntArray(6000) { 180 + rng.nextInt(850) }
        val picture = media(directory, "video.mp4", audio, moving = moving)
        val alternativePicture = if (audioOnlyAlternative) media(directory, "alternative.m4a", audio, audioOnly = true) else picture
        val weak = media(directory, "first-song.mp4", IntArray(6000) { 380 })
        val alternateReads = AtomicInteger()
        fun backend(alternate: Boolean) = object : StreamBackend {
            override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution) =
                StreamInfo("https://example.invalid/video.mp4", durationMs = 140_000, width = 640, height = 360)
            override suspend fun resolveAudio(videoId: String): AudioStream {
                if (alternate) alternateReads.incrementAndGet()
                return if (alternate && (videoId == "VIDEO000001" || recover)) alternativePicture
                    else if (videoId == "VIDEO000001") picture else weak
            }
        }
        try {
            OpenCanvas.configure(OpenCanvasConfig(backend(true), backend(false), File(directory, "maps"), communityMapsUrl = null))
            val result = withTimeout(10_000) {
                OpenCanvas.resolve("Encoding fixture", "Fixture Artist", 140,
                    mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = "TRACK000001", videoId = "VIDEO000001")
            }
            assertEquals(if (recover && moving) SyncStage.MEASURED else SyncStage.NONE, result?.syncStage)
            assertTrue(alternateReads.get() >= 2, "readable weak headers must still try the matching encoder pair")
        } finally {
            OpenCanvas.configure(OpenCanvasConfig(backend(false), instantBackend = null, cacheDirectory = null, communityMapsUrl = null))
            directory.deleteRecursively()
        }
    }
    @Test fun aReadableWeakEncodingDoesNotPreventTryingTheOtherClient() = exercise(true)
    @Test fun twoWeakEncodingsStillDoNotProduceAVerifiedMap() = exercise(false)
    @Test fun anAudioOnlyTimingAlternativeDoesNotEraseEvidenceOfStillArtwork() =
        exercise(recover = true, moving = false, audioOnlyAlternative = true)
}
