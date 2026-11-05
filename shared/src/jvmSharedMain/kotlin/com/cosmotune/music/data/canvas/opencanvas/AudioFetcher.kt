package com.opencanvas.core.stream

import com.opencanvas.core.sync.RangeReader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI

/** A slice of an audio stream and the size of the whole stream when the server said so. */
internal class AudioSlice(val bytes: ByteArray, val totalLength: Long)

/**
 * Reads an [AudioStream] for the MP4 parser. The first [HEAD_BYTES] arrive in a single request and
 * answer every read that falls inside them (the file type box, the `moov` box of a progressive
 * file), so a typical file costs one round trip; a read beyond them is fetched on its own.
 */
internal class StreamReader(private val fetcher: AudioFetcher, private val stream: AudioStream) : RangeReader {
    private var head: ByteArray? = null
    override var length: Long = stream.contentLength.takeIf { it > 0L } ?: -1L
        private set

    override suspend fun read(from: Long, count: Int): ByteArray {
        val cached = head ?: fetcher.read(stream, 0L, HEAD_BYTES).also {
            head = it.bytes
            if (it.totalLength > 0L) length = it.totalLength else if (it.bytes.size < HEAD_BYTES) length = it.bytes.size.toLong()
        }.bytes
        val end = from + count
        if (end <= cached.size) return cached.copyOfRange(from.toInt(), end.toInt())
        if (length >= 0L && from >= length) return ByteArray(0)
        val slice = fetcher.read(stream, from, count.toLong())
        if (slice.totalLength > 0L) length = slice.totalLength
        return slice.bytes
    }

    private companion object {
        const val HEAD_BYTES = 320L * 1024L
    }
}

/**
 * Downloads parts of an [AudioStream] with HTTP range requests, several at once, so a few hundred
 * kilobytes arrive in one round-trip time and a whole file in a handful of them. Streams whose URL
 * is a `file:` URI are read from disk, which lets an app align a song it plays from local storage.
 */
internal class AudioFetcher(private val http: OkHttpClient) {

    /**
     * Reads up to [length] bytes of [stream] starting at [from]. The slice is shorter than
     * requested when the stream ends first and empty when [from] is at or past its end.
     *
     * @throws IOException when the server refuses or does not support range requests.
     */
    suspend fun read(stream: AudioStream, from: Long, length: Long, parallelism: Int = DEFAULT_PARALLELISM): AudioSlice {
        require(from >= 0L && length > 0L) { "from=$from length=$length" }
        if (stream.url.startsWith("file:")) return readFile(stream.url, from, length)
        val chunk = stream.maxRangeBytes.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
        val starts = generateSequence(from) { it + chunk }.takeWhile { it < from + length }.toList()
        val permits = Semaphore(parallelism.coerceAtLeast(1))
        val parts = coroutineScope {
            starts.map { start ->
                async { permits.withPermit { readChunk(stream, start, minOf(chunk, from + length - start)) } }
            }.awaitAll()
        }
        val whole = parts.firstOrNull { it.totalLength > 0L }?.totalLength ?: 0L
        val contiguous = parts.takeWhile { it.bytes.isNotEmpty() }
        val out = ByteArray(contiguous.sumOf { it.bytes.size })
        var at = 0
        for (part in contiguous) {
            part.bytes.copyInto(out, at)
            at += part.bytes.size
        }
        return AudioSlice(out, whole)
    }

    private suspend fun readChunk(stream: AudioStream, start: Long, size: Long): AudioSlice =
        withRetry(listOf(2_000L, 10_000L)) { timeoutMs -> readChunkOnce(stream, start, size, timeoutMs) }

    private suspend fun readChunkOnce(stream: AudioStream, start: Long, size: Long, timeoutMs: Long): AudioSlice {
        val builder = Request.Builder().url(stream.url).header("Range", "bytes=$start-${start + size - 1}")
        stream.headers.forEach { (name, value) -> builder.header(name, value) }
        // At most the asked-for size is read: a server that ignores the range and answers 200 with the
        // whole file must not be downloaded to the end.
        val answer = http.newCall(builder.build()).within(timeoutMs).fetch(limit = size)
        return when {
            answer.code == 416 -> AudioSlice(ByteArray(0), 0L)
            answer.code == 206 -> AudioSlice(answer.body, answer.headers["Content-Range"]?.substringAfter('/')?.toLongOrNull() ?: 0L)
            answer.code == 200 && start == 0L -> AudioSlice(answer.body, answer.contentLength.takeIf { it > 0L } ?: 0L)
            else -> throw IOException("range request for $start-${start + size - 1} answered HTTP ${answer.code}")
        }
    }

    private fun readFile(uri: String, from: Long, length: Long): AudioSlice {
        val file = File(URI(uri))
        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (from >= total) return AudioSlice(ByteArray(0), total)
            val bytes = ByteArray(minOf(length, total - from).toInt())
            raf.seek(from)
            raf.readFully(bytes)
            return AudioSlice(bytes, total)
        }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 4
        private const val MIN_CHUNK_BYTES = 64L * 1024L
        private const val MAX_CHUNK_BYTES = 4L * 1024L * 1024L
    }
}
