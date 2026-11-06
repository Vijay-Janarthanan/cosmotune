package com.cosmotune.watch.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class WatchHttpException(val code: Int, message: String) : IOException(message)

/**
 * The HTTP every Watch plugin goes through: GETs (and the few POSTs sign-in needs), a memory and disk cache with a time to
 * live per call, one request in flight per address however many callers ask, a size cap on every body, and gzip guides
 * (`.xml.gz`) opened transparently. Nothing is ever executed from what comes back.
 */
class WatchHttp(
    val client: OkHttpClient,
    private val cacheDir: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val userAgent: String = USER_AGENT,
) {
    private class Cached(val body: String, val atMs: Long)

    private val memory = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

    /** The body of [url] as text, from cache when younger than [ttlMs] (0 = always fetch). Stale cache is used when offline. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), ttlMs: Long = DEFAULT_TTL, maxBytes: Long = MAX_TEXT): String {
        val key = cacheKey("GET", url, headers)
        memory[key]?.takeIf { ttlMs > 0 && now() - it.atMs < ttlMs }?.let { return it.body }
        diskRead(key, ttlMs)?.let { if (it.first.length <= MEMORY_MAX_CHARS) memory[key] = Cached(it.first, it.second); return it.first }
        val mine = CompletableDeferred<String>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        try {
            val body = try {
                fetch(request(url, headers).get().build(), maxBytes)
            } catch (e: IOException) {
                // Offline or the server is down: an expired copy is better than nothing.
                diskRead(key, Long.MAX_VALUE)?.first ?: memory[key]?.body ?: throw e
            }
            if (ttlMs > 0) { if (body.length <= MEMORY_MAX_CHARS) memory[key] = Cached(body, now()); diskWrite(key, body) }
            mine.complete(body)
            return body
        } catch (e: Throwable) {
            mine.completeExceptionally(e); throw e
        } finally { inFlight.remove(key, mine) }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), ttlMs: Long = DEFAULT_TTL): JsonElement =
        json.parseToJsonElement(getText(url, headers + ("Accept" to "application/json"), ttlMs))

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), contentType: String = "application/json"): JsonElement =
        json.parseToJsonElement(fetch(request(url, headers + ("Accept" to "application/json")).post(body.toRequestBody(contentType.toMediaType())).build(), MAX_TEXT))

    /** A POST whose answer is ignored (progress reports). Failures are swallowed. */
    suspend fun postQuietly(url: String, body: String, headers: Map<String, String> = emptyMap()) {
        runCatching { fetch(request(url, headers).post(body.toRequestBody("application/json".toMediaType())).build(), 1L shl 20) }
    }

    /**
     * Opens a large body ([url], a guide or a playlist of many MB) as a stream for [read], kept on disk for [ttlMs] when a
     * cache folder exists. Gzip is detected by its magic bytes.
     */
    suspend fun <T> stream(url: String, headers: Map<String, String> = emptyMap(), ttlMs: Long = DEFAULT_TTL, maxBytes: Long = MAX_STREAM, read: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val key = cacheKey("STREAM", url, headers)
            val file = cacheDir?.let { File(it, "$key.bin") }
            if (file != null && file.isFile && now() - file.lastModified() < ttlMs) return@withContext file.inputStream().use { read(ungzip(it)) }
            val response = try { execute(request(url, headers).get().build()) } catch (e: IOException) {
                if (file != null && file.isFile) return@withContext file.inputStream().use { read(ungzip(it)) }
                throw e
            }
            response.use { r ->
                val body = r.body
                if (file == null) return@use read(ungzip(Limited(body.byteStream(), maxBytes)))
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, file.name + ".part")
                Limited(body.byteStream(), maxBytes).use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
                file.inputStream().use { read(ungzip(it)) }
            }
        }

    /** The status, final address and first bytes of [url] (a range GET), for sniffing disguised HLS or DASH. */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            execute(request(url, headers + ("Range" to "bytes=0-2047")).get().build()).use { r ->
                val bytes = r.body.byteStream().readNBytesCompat(2048)
                Probe(r.code, r.request.url.toString(), r.header("Content-Type"), bytes)
            }
        }.getOrNull()
    }

    class Probe(val code: Int, val finalUrl: String, val contentType: String?, val head: ByteArray) {
        val text: String get() = head.toString(Charsets.UTF_8)
    }

    fun invalidate(url: String, headers: Map<String, String> = emptyMap()) {
        val key = cacheKey("GET", url, headers); memory.remove(key); cacheDir?.let { File(it, "$key.txt").delete() }
    }

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url).apply {
        if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", userAgent)
        headers.forEach { (k, v) -> header(k, v) }
    }

    private suspend fun fetch(request: Request, maxBytes: Long): String = withContext(Dispatchers.IO) {
        execute(request).use { r -> Limited(r.body.byteStream(), maxBytes).use { ungzip(it).readBytes().toString(Charsets.UTF_8) } }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    cont.resumeWithException(WatchHttpException(code, "HTTP $code from ${request.url.host}"))
                } else cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    private fun diskRead(key: String, ttlMs: Long): Pair<String, Long>? {
        if (ttlMs <= 0) return null
        val file = cacheDir?.let { File(it, "$key.txt") } ?: return null
        if (!file.isFile || now() - file.lastModified() >= ttlMs) return null
        return runCatching { file.readText() to file.lastModified() }.getOrNull()
    }

    private fun diskWrite(key: String, body: String) {
        val dir = cacheDir ?: return
        runCatching {
            dir.mkdirs()
            val part = File(dir, "$key.part"); part.writeText(body)
            val target = File(dir, "$key.txt")
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
        }
    }

    private fun cacheKey(method: String, url: String, headers: Map<String, String>): String =
        MessageDigest.getInstance("SHA-256").digest("$method $url ${headers.toSortedMap()}".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    private class Limited(private val inner: InputStream, private val max: Long) : InputStream() {
        private var read = 0L
        private fun count(n: Int): Int { if (n > 0) { read += n; if (read > max) throw IOException("Response is larger than ${max shr 20} MB") }; return n }
        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(inner.read(b, off, len))
        override fun close() = inner.close()
    }

    companion object {
        const val DEFAULT_TTL = 30 * 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val MAX_TEXT = 32L shl 20
        /** Bodies kept in memory as well as on disk are at most this long: a list of many MB stays on disk only. */
        const val MEMORY_MAX_CHARS = 512 * 1024
        const val MAX_STREAM = 256L shl 20
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        fun ungzip(input: InputStream): InputStream {
            val buffered = if (input.markSupported()) input else BufferedInputStream(input)
            buffered.mark(2)
            val a = buffered.read(); val b = buffered.read()
            buffered.reset()
            return if (a == 0x1f && b == 0x8b) GZIPInputStream(buffered) else buffered
        }

        private fun InputStream.readNBytesCompat(n: Int): ByteArray {
            val out = ByteArray(n); var total = 0
            while (total < n) { val r = read(out, total, n - total); if (r < 0) break; total += r }
            return out.copyOf(total)
        }
    }
}
