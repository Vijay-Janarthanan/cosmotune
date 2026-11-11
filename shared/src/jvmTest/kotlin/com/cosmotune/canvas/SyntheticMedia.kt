package com.cosmotune.canvas

import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.stream.AudioStream
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.StreamInfo
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Tiny MP4 files whose sample tables are all the sync measurement reads (see CanvasEncodingFallbackTest): the same audio
 * frame sizes line up, a picture of near-empty frames is a still.
 */
internal object SyntheticMedia {
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

    /** The song's audio frame sizes: the recording every matching video carries. */
    fun recording(seed: Int = 42): IntArray {
        val rng = Random(seed)
        return IntArray(6000) { 180 + rng.nextInt(850) }
    }

    fun file(directory: File, name: String, audio: IntArray, moving: Boolean = true): AudioStream {
        val rng = Random(71)
        val data = box("ftyp", "isom".toByteArray(), number(0), "isomiso2".toByteArray()) +
            box("moov", track(1, "vide", 90_000,
                IntArray(4200) { if (moving) 4000 + rng.nextInt(12000) else if (it % 30 == 0) 30_000 else 6 }, 3000),
                track(2, "soun", 44_100, audio, 1024)) + box("mdat", ByteArray(2048))
        val file = File(directory, name).apply { writeBytes(data) }
        return AudioStream(file.toURI().toString(), durationMs = 140_000, contentLength = file.length())
    }

    /** A backend that serves [files] by video id; any other id cannot be read. */
    fun backend(files: Map<String, AudioStream>): StreamBackend = object : StreamBackend {
        override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution) =
            StreamInfo("https://example.invalid/$videoId.mp4", durationMs = 140_000, width = 640, height = 360)

        override suspend fun resolveAudio(videoId: String): AudioStream =
            files[videoId] ?: throw java.io.IOException("no file for $videoId")
    }
}
