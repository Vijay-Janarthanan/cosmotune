package com.opencanvas.core.stream

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/**
 * A readable, seekable view of a remote media file that is fetched in HTTP byte ranges, YouTube
 * style: the first request is small so playback can start almost immediately, every request after
 * it is larger so the buffer grows ahead of the playhead, and a seek abandons whatever is in flight
 * and fetches a small range at the new position first.
 *
 * Why not one big GET? Googlevideo throttles an un-ranged download of a stream URL to roughly the
 * playback rate (measured: ~113 kB/s for an 18.6 MB 720p clip, about three minutes), while ranged
 * requests run at line speed (~2 MB/s). A clip only needs ~70 kB/s to play, so reading it through
 * ranges starts in well under a second and then stays ahead of the decoder.
 *
 * Reads block until the bytes at the current position have arrived. Everything fetched is kept
 * (up to [maxBufferBytes]) so a looping clip is replayed from memory, and once the whole file is
 * present [writeCompleteTo] can persist it as an ordinary cache file.
 *
 * Thread model: one background fetcher thread and any number of caller threads (serialised by one
 * monitor). [close] stops the fetcher and aborts the request in flight.
 */
class ProgressiveRangeSource(
    private val url: String,
    private val client: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    /** Size of the first request, and of the first one after every seek (~2-3 s of a 720p clip). */
    private val initialChunkBytes: Int = DEFAULT_INITIAL_CHUNK,
    /** Requests double in size up to this many bytes. */
    private val maxChunkBytes: Int = DEFAULT_MAX_CHUNK,
    /** The fetcher pauses when it is this far ahead of the reader. */
    private val aheadLimitBytes: Long = DEFAULT_AHEAD_LIMIT,
    /** Above this much held in memory, blocks well behind the reader are dropped. */
    private val maxBufferBytes: Long = DEFAULT_MAX_BUFFER,
    /** How long a read waits for bytes before giving up. */
    private val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // a monitor needs wait()/notifyAll()
    private val lock = Object()
    private val blocks = TreeMap<Long, ByteArray>()
    private val requests = ArrayList<Pair<Long, Int>>()
    private var bufferedBytes = 0L
    private var totalLength = -1L
    private var readPosition = 0L
    private var chunkBytes = initialChunkBytes
    // Bumped by every seek that lands outside the buffer, so a response that was already on its way
    // when the seek happened cannot grow the chunk size of the fetches that follow it.
    private var generation = 0
    private var closed = false
    private var failure: IOException? = null
    private var inFlight: Call? = null

    /** The (offset, size) of every range request issued so far - for tests, logs and benchmarks. */
    val requestLog: List<Pair<Long, Int>> get() = synchronized(lock) { requests.toList() }

    private val fetcher = Thread({ fetchLoop() }, "opencanvas-range-fetch").apply { isDaemon = true }

    init {
        require(initialChunkBytes > 0 && maxChunkBytes >= initialChunkBytes) { "bad chunk sizes" }
        fetcher.start()
    }

    /** Total size in bytes, waiting for the first response if it has not arrived yet. */
    fun length(): Long {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + readTimeoutMillis
            while (totalLength < 0) {
                failure?.let { throw it }
                if (closed) throw IOException("closed")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IOException("timed out waiting for the size of $url")
                lock.wait(left.coerceAtMost(200))
            }
            return totalLength
        }
    }

    val position: Long get() = synchronized(lock) { readPosition }

    /**
     * Reads up to [len] bytes at the current position, blocking until at least one is available.
     * Returns -1 at the end of the file.
     */
    fun read(into: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + readTimeoutMillis
            while (!closed) {
                failure?.let { throw it }
                if (totalLength in 0..readPosition) return -1
                val entry = blocks.floorEntry(readPosition)
                if (entry != null && readPosition < entry.key + entry.value.size) {
                    val skip = (readPosition - entry.key).toInt()
                    val n = minOf(len, entry.value.size - skip)
                    System.arraycopy(entry.value, skip, into, off, n)
                    readPosition += n
                    lock.notifyAll() // the fetcher may have been parked on the ahead limit
                    return n
                }
                lock.notifyAll()
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IOException("timed out reading $url at byte $readPosition")
                lock.wait(left.coerceAtMost(200))
            }
        }
        throw IOException("closed")
    }

    /**
     * Moves the read position. Landing outside what is already buffered abandons the request in
     * flight and restarts the chunk growth from [initialChunkBytes], so the bytes the playhead needs
     * come back first and quickly.
     */
    fun seek(position: Long) {
        synchronized(lock) {
            val target = position.coerceAtLeast(0)
            if (target == readPosition) return
            readPosition = target
            if (!isBuffered(target)) {
                chunkBytes = initialChunkBytes
                generation++
                inFlight?.cancel()
            }
            lock.notifyAll()
        }
    }

    /** True once every byte of the file is held in memory. */
    val isComplete: Boolean
        get() = synchronized(lock) { totalLength >= 0 && firstMissing(0) >= totalLength }

    /** Writes the whole file to [target] (via a temp file) if it is fully buffered. */
    fun writeCompleteTo(target: Path): Boolean {
        val snapshot = synchronized(lock) {
            if (totalLength < 0 || firstMissing(0) < totalLength) return false
            blocks.values.toList()
        }
        val parent = target.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        Files.newOutputStream(part).use { out -> snapshot.forEach { out.write(it) } }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /** Blocks until the whole file is buffered, [timeoutMillis] passes, or the source fails. */
    fun awaitComplete(timeoutMillis: Long): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!closed) {
                failure?.let { throw it }
                if (totalLength >= 0 && firstMissing(0) >= totalLength) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left.coerceAtMost(200))
            }
        }
        return false
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            inFlight?.cancel()
            lock.notifyAll()
        }
        fetcher.interrupt()
    }

    // ── fetcher ──────────────────────────────────────────────────────────────

    private fun fetchLoop() {
        while (true) {
            val (start, size) = nextRequest() ?: return
            val issuedIn = synchronized(lock) { generation }
            try {
                fetch(start, size, issuedIn)
            } catch (e: IOException) {
                synchronized(lock) {
                    if (closed) return
                    // A cancelled call (a seek) is not a failure; anything else is surfaced to readers.
                    if (inFlight?.isCanceled() != true) {
                        failure = e
                        lock.notifyAll()
                        return
                    }
                    inFlight = null
                }
            }
        }
    }

    /** The next range to fetch, waiting while there is nothing worth fetching; null once closed. */
    private fun nextRequest(): Pair<Long, Int>? {
        synchronized(lock) {
            while (!closed) {
                val next = firstMissing(readPosition)
                val atEnd = totalLength in 0..next
                // Ahead of the reader by the limit means idle, unless the reader is itself waiting
                // on bytes that are not buffered - that is always served first.
                if (!atEnd && (next - readPosition < aheadLimitBytes || !isBuffered(readPosition))) {
                    var want = chunkBytes.toLong()
                    blocks.higherKey(next)?.let { want = minOf(want, it - next) }
                    if (totalLength >= 0) want = minOf(want, totalLength - next)
                    val size = want.coerceAtLeast(1).toInt()
                    requests += next to size
                    return next to size
                }
                lock.wait(200)
            }
        }
        return null
    }

    private fun fetch(start: Long, size: Int, issuedIn: Int) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$start-${start + size - 1}")
        }.build()
        val call = client.newCall(request)
        synchronized(lock) { inFlight = call }
        call.execute().use { response ->
            when (response.code) {
                206 -> {
                    val body = response.body?.bytes() ?: throw IOException("empty range response")
                    val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    store(start, body, total, issuedIn)
                }
                200 -> {
                    // The server ignored the range and sent everything.
                    if (start != 0L) throw IOException("server ignored the range request")
                    val body = response.body?.bytes() ?: throw IOException("empty response")
                    store(0, body, body.size.toLong(), issuedIn)
                }
                416 -> synchronized(lock) {
                    // Asked past the end: that is where the file stops.
                    if (totalLength < 0) totalLength = start
                    lock.notifyAll()
                }
                else -> throw IOException("HTTP ${response.code} for range at $start")
            }
        }
        synchronized(lock) { if (inFlight === call) inFlight = null }
    }

    private fun store(start: Long, bytes: ByteArray, total: Long?, issuedIn: Int) {
        synchronized(lock) {
            if (total != null && total >= 0) totalLength = total
            if (bytes.isNotEmpty() && !blocks.containsKey(start)) {
                blocks[start] = bytes
                bufferedBytes += bytes.size
                trim()
            }
            if (issuedIn == generation) {
                chunkBytes = minOf(chunkBytes.toLong() * 2, maxChunkBytes.toLong()).toInt()
            }
            lock.notifyAll()
        }
    }

    // ── helpers (call with the lock held) ────────────────────────────────────

    private fun isBuffered(position: Long): Boolean {
        val e = blocks.floorEntry(position) ?: return false
        return position < e.key + e.value.size
    }

    private fun firstMissing(from: Long): Long {
        var p = from
        while (true) {
            val e = blocks.floorEntry(p) ?: return p
            val end = e.key + e.value.size
            if (end <= p) return p
            p = end
        }
    }

    /** Drops the oldest blocks that are well behind the reader once memory use gets out of hand. */
    private fun trim() {
        if (bufferedBytes <= maxBufferBytes) return
        val keepFrom = readPosition - TRIM_KEEP_BEHIND
        val entries = blocks.entries.iterator()
        while (bufferedBytes > maxBufferBytes && entries.hasNext()) {
            val e = entries.next()
            if (e.key + e.value.size > keepFrom) break
            bufferedBytes -= e.value.size
            entries.remove()
        }
    }

    companion object {
        const val DEFAULT_INITIAL_CHUNK = 192 * 1024
        const val DEFAULT_MAX_CHUNK = 4 * 1024 * 1024
        const val DEFAULT_AHEAD_LIMIT = 48L * 1024 * 1024
        const val DEFAULT_MAX_BUFFER = 96L * 1024 * 1024
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        private const val TRIM_KEEP_BEHIND = 2L * 1024 * 1024
    }
}
