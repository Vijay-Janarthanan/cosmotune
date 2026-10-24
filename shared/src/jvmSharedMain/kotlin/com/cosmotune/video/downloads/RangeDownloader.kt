package com.cosmotune.video.downloads

import com.cosmotune.video.player.net.GooglevideoRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** The stream's address stopped working (HTTP 403) and a fresh one did not help: the download fails ("Try again" asks anew). */
class SourceExpired(message: String) : IOException(message)

/**
 * Fetches one stream into a file in ranges of [chunkBytes] (2 MiB, spec 6.2; plan task Y-24, step 4), appending: a download
 * resumes from the file's length. A `googlevideo.com` address gets `&range=` and `&rn=` and a POST with
 * [GooglevideoRequests.POST_BODY] (the rules the engines share); any other address a `Range` header. On a 403 it asks once for a
 * fresh address (the caller matches the same itag in `source.streams`) and goes on from the bytes it has; a second 403 in a row
 * is a [SourceExpired]. Cancellation is checked before every range and cancels the request in flight. No cookies: the client
 * is built without a cookie jar (no account, spec 11).
 */
class RangeDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val chunkBytes: Long = GooglevideoRequests.CHUNK_BYTES,
    private val isGooglevideo: (String) -> Boolean = GooglevideoRequests::isVideoplayback,
    private val retries: Int = 2,
    private val retryDelayMs: Long = 1_000,
) {
    /**
     * Downloads [url] into [file] with [headers] (the stream set's, spec 6.2), from the file's current length to the end;
     * [expectedBytes] is the stream's content length when known (the end is then exact; else a short range or the server's
     * `Content-Range` ends it). [refresh] gives a fresh address for the same stream after a 403, or null when there is none.
     * [onProgress] gets the bytes on disk and the total when known. Returns the file's length.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        file: File,
        expectedBytes: Long? = null,
        refresh: (suspend () -> String?)? = null,
        onProgress: (written: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long {
        file.parentFile?.mkdirs()
        var current = url
        var total = expectedBytes?.takeIf { it > 0 }
        var written = if (file.exists()) file.length() else 0L
        if (total != null && written > total) { truncate(file, 0); written = 0 }
        var requestNumber = 1
        var refreshedSinceProgress = false
        var failures = 0
        onProgress(written, total)
        while (total == null || written < total) {
            currentCoroutineContext().ensureActive()
            val end = (written + chunkBytes - 1).let { if (total != null) minOf(it, total - 1) else it }
            val asked = end - written + 1
            val outcome = try {
                fetch(current, headers, written, end, requestNumber++, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++failures > retries) throw e
                delay(retryDelayMs * failures)
                continue
            }
            when (outcome) {
                is Outcome.Forbidden -> {
                    val fresh = if (!refreshedSinceProgress && refresh != null) refresh() else null
                    if (fresh == null) throw SourceExpired("HTTP 403 for the stream" + if (refreshedSinceProgress) " after a fresh address" else "")
                    current = fresh
                    refreshedSinceProgress = true
                }
                is Outcome.Beyond -> {
                    // 416: nothing at or after this offset. The end, if anything was fetched at all.
                    if (written == 0L) throw IOException("HTTP 416 at the start of the stream")
                    return written
                }
                is Outcome.Restarted -> {
                    // the server ignored the range and sent the whole stream from the start
                    written = file.length()
                    onProgress(written, total ?: written)
                    return written
                }
                is Outcome.Got -> {
                    failures = 0
                    refreshedSinceProgress = false
                    if (outcome.total != null && total == null) total = outcome.total
                    written += outcome.bytes
                    onProgress(written, total)
                    if (outcome.bytes == 0L || (total == null && outcome.bytes < asked)) return written
                }
            }
        }
        return written
    }

    private sealed interface Outcome {
        data object Forbidden : Outcome
        data object Beyond : Outcome
        data object Restarted : Outcome
        data class Got(val bytes: Long, val total: Long?) : Outcome
    }

    /** One range request, its body appended to [file] at [start]. */
    private suspend fun fetch(url: String, headers: Map<String, String>, start: Long, end: Long, number: Int, file: File): Outcome {
        val builder = Request.Builder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (isGooglevideo(url)) {
            builder.url(GooglevideoRequests.withRequestNumber(GooglevideoRequests.withRange(url, start, end), number))
            builder.post(GooglevideoRequests.POST_BODY.toRequestBody(null))
        } else {
            builder.url(url).get().header("Range", "bytes=$start-$end")
        }
        return client.newCall(builder.build()).await { response ->
            when {
                response.code == 403 -> Outcome.Forbidden
                response.code == 416 -> Outcome.Beyond
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                response.code == 200 && !isGooglevideo(url) && start > 0 -> {
                    // a server without range support: start over with the whole body
                    truncate(file, 0)
                    append(response, file, 0)
                    Outcome.Restarted
                }
                else -> Outcome.Got(append(response, file, start), totalOf(response))
            }
        }
    }

    /** Writes the body at [offset] (dropping anything after it, a half-written range of an earlier run); returns the bytes written. */
    private fun append(response: Response, file: File, offset: Long): Long {
        if (file.exists() && file.length() > offset) truncate(file, offset)
        val body = response.body
        var count = 0L
        FileOutputStream(file, true).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    count += n
                }
            }
            out.fd.sync()
        }
        return count
    }

    private fun truncate(file: File, length: Long) = RandomAccessFile(file, "rw").use { it.setLength(length) }

    /** The total of a `Content-Range: bytes a-b/T` header, or null. */
    private fun totalOf(response: Response): Long? =
        response.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()

    companion object {
        /** A client for Video's downloads: no cookie jar (no account), redirects followed, generous read timeout for large ranges. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Runs the call on the I/O dispatcher and hands its response to [block]; when the coroutine is cancelled the call is cancelled
 * too, which closes the socket so a blocked read ends at once. It returns only once [block] has stopped (nothing writes after
 * it), and a cancelled caller always gets the cancellation, not the closed socket's I/O error.
 */
internal suspend fun <T> Call.await(block: (Response) -> T): T {
    val call = this
    try {
        return coroutineScope {
            val result = async(Dispatchers.IO) {
                call.execute().use { response ->
                    // The response may arrive before the cancellation handler gets CPU time.
                    // A paused transfer must not append that late response to its partial file.
                    currentCoroutineContext().ensureActive()
                    block(response)
                }
            }
            try {
                result.await()
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            }
        }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    }
}
