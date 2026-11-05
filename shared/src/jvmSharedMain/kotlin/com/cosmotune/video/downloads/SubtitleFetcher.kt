package com.cosmotune.video.downloads

import com.cosmotune.video.model.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fetches a subtitle track beside a download (plan task Y-24, step 2): a plain GET, tried three times (two retries), the text
 * converted to WebVTT when the source served TTML, timed text or SubRip ([SubtitleFiles.toVtt]), written to a temporary file
 * and renamed into place. No cookies (the client has no cookie jar).
 */
class SubtitleFetcher(
    private val client: OkHttpClient = RangeDownloader.defaultClient(),
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 1_000,
) {
    /** Writes [track] as WebVTT to [file]; throws [IOException] when every attempt failed. */
    suspend fun fetch(track: SubtitleTrack, file: File, headers: Map<String, String> = emptyMap()) {
        var last: IOException? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(retryDelayMs * attempt)
            try {
                val text = get(track.url, headers)
                val vtt = SubtitleFiles.toVtt(text, SubtitleFormat.of(track.mimeType, track.url, text))
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, file.name + ".part")
                    temp.writeText(vtt)
                    if (!temp.renameTo(file)) {
                        file.delete()
                        if (!temp.renameTo(file)) throw IOException("Could not write ${file.name}")
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("No attempt was made")
    }

    private suspend fun get(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).await { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }
}
