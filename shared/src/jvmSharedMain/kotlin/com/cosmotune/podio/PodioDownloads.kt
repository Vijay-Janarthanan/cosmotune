package com.cosmotune.podio

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request

data class PodioDownloadFormat(val extension: String, val mimeType: String)

/** Publisher enclosures are kept as their own recording, with their real audio container. */
object PodioDownloads {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun isEpisode(id: String): Boolean = id.startsWith("podio:episode:")

    /** Selecting an offline candidate is pure; the audio engine checks file existence on IO. */
    fun withOfflinePath(original: com.cosmotune.music.data.model.Song,
        downloaded: com.cosmotune.music.data.model.Song?): com.cosmotune.music.data.model.Song {
        val saved = downloaded?.takeIf { it.videoId == original.videoId } ?: return original
        val path = saved.localPath ?: saved.localUri?.let { raw ->
            runCatching { URI(raw).takeIf { it.scheme == "file" }?.let { java.io.File(it).path } }.getOrNull()
        }
        return path?.takeIf { java.io.File(it).isAbsolute }?.let { original.copy(localPath = it) } ?: original
    }

    fun format(url: String, contentType: String? = null, prefix: ByteArray? = null): PodioDownloadFormat? {
        prefix?.let { bytes ->
            val delivered = com.cosmotune.audio.DeliveredAudio.inspect(bytes)
            if (delivered?.audio == false) return null
            val actual = when (delivered?.container) {
                "mp4" -> "m4a"; "adts" -> "aac"; "mp3", "ogg", "wav", "flac" -> delivered.container
                else -> null
            }
            actual?.let { return format("https://audio.invalid/episode.$it") }
            if (bytes.size >= 4 && bytes.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)) return PodioDownloadFormat("webm", "audio/webm")
        }
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val extension = when (type) {
            "audio/mpeg", "audio/mp3", "audio/x-mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/webm" -> "webm"
            "", "application/octet-stream", "binary/octet-stream" -> runCatching { URI(url).path.substringAfterLast('.').lowercase() }.getOrNull()
            else -> return null
        }
        return when (extension) {
            "mp3" -> PodioDownloadFormat("mp3", "audio/mpeg")
            "m4a", "mp4" -> PodioDownloadFormat("m4a", "audio/mp4")
            "aac" -> PodioDownloadFormat("aac", "audio/aac")
            "flac" -> PodioDownloadFormat("flac", "audio/flac")
            "ogg", "opus" -> PodioDownloadFormat("ogg", "audio/ogg")
            "wav" -> PodioDownloadFormat("wav", "audio/x-wav")
            "webm" -> PodioDownloadFormat("webm", "audio/webm")
            else -> null
        }
    }

    suspend fun inspect(url: String): PodioDownloadFormat = suspendCancellableCoroutine { continuation ->
        val address = requireNotNull(PodioCatalogue.httpUrl(url)) { "This episode has no publisher audio URL." }
        val call = client.newCall(Request.Builder().url(address).header("Range", "bytes=0-65535").header("Accept-Encoding", "identity")
            .header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    val detectedFormat = response.use { reply ->
                        check(reply.isSuccessful) { "The episode publisher returned HTTP ${reply.code}." }
                        val output = java.io.ByteArrayOutputStream()
                        requireNotNull(reply.body).byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (output.size() < 65536) {
                                val count = input.read(buffer, 0, minOf(buffer.size, 65536 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        check(output.size() > 0) { "The episode publisher sent an empty file." }
                        requireNotNull(format(reply.request.url.toString(), reply.header("Content-Type"), output.toByteArray())) {
                            "This publisher's enclosure is not a supported downloadable audio file."
                        }
                    }
                    if (continuation.isActive) continuation.resume(detectedFormat)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}
