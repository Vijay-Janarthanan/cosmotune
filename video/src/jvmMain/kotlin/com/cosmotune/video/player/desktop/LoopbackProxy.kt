package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.net.GooglevideoRequests
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Call
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop engine's loopback proxy (spec 6.3, plan decision W8): a JDK `HttpServer` on `127.0.0.1` with a random port and a
 * random path token that answers mpv's range requests by fetching the source in 2 MiB pieces with the request rules both
 * engines share ([GooglevideoRequests]: `&range=`, `&rn=`, the stream set's headers, a POST with `x\0`, and a GET with a `Range`
 * header once the source answers a POST with 400 or 405). mpv then reads as fast as its cache wants instead of being throttled
 * to playback speed, and a 403 from the source (the addresses expired) becomes [onExpired] for the engine's `SOURCE_EXPIRED`.
 *
 * Addresses are `http://127.0.0.1:<port>/<token>/<n>`: [token] is one random 32-hex value per session ([reset] starts a new one,
 * so the addresses of an earlier load answer 404), `n` the stream's number in the session. A `GET` or `HEAD` with a `Range:
 * bytes=a-b`, `bytes=a-` or `bytes=-n` header is answered 206 with a `Content-Range`, without one 200. The total length is the
 * caller's [register] `contentLength`, else the address's `clen`, else what the first upstream answer says (the total of its
 * `Content-Range`, or its `Content-Length` when that is shorter than the piece asked for or when it answered the whole file).
 *
 * The HTTP client has no cookie jar (Video sends no cookies, spec 11) and asks for identity encoding, so lengths are byte counts.
 * The JDK's HTTP server lives in the module `jdk.httpserver`: a packaged runtime must include it.
 */
class LoopbackProxy(
    private val onExpired: (token: String) -> Unit = {},
    private val client: OkHttpClient = defaultClient(),
    private val onBandwidth: (kbps: Int) -> Unit = {},
) : AutoCloseable {

    private class Stream(val url: String, val headers: Map<String, String>, val googlevideo: Boolean, knownLength: Long?) {
        /** The total length in bytes, or -1 until something tells it. */
        @Volatile var length: Long = knownLength ?: -1

        /** Whether the source refused the POST and is read with GET and a `Range` header from now on. */
        @Volatile var useGet = false

        @Volatile var contentType: String? = null

        /** The next `rn`: every request of the stream counts, from 0 (NewPipe's `YoutubeHttpDataSource`). */
        val requestNumber = AtomicInteger(0)
    }

    private class Session(val token: String) {
        val streams = CopyOnWriteArrayList<Stream>()
    }

    /** The source said 403: the stream set's addresses expired. */
    private class Expired : IOException("the source answered 403")

    /** The source answered something the proxy cannot pass on. */
    private class Refused(val code: Int) : IOException("the source answered $code")

    /** The reader (mpv) closed its connection. */
    private class ReaderGone(cause: IOException) : IOException(cause)

    private val random = SecureRandom()
    private val executor: ExecutorService = Executors.newFixedThreadPool(THREADS) { task ->
        Thread(task, "cosmotune-video-proxy").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), BACKLOG)
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile private var session = Session(newToken())
    @Volatile private var closed = false
    @Volatile private var bandwidth = -1.0

    /** The port the proxy listens on, on 127.0.0.1. */
    val port: Int

    /** The current session's path token (32 hex digits). */
    val token: String get() = session.token

    /** The source's throughput as last measured (a moving average over the 2 MiB pieces), in kilobits per second, or null before any. */
    val bandwidthKbps: Int? get() = bandwidth.takeIf { it > 0 }?.toInt()

    /** Whether a request of this session failed for the network (no answer at all) rather than with an HTTP status. */
    @Volatile var networkFailed = false
        private set

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
        port = server.address.port
    }

    /**
     * Makes [upstreamUrl] readable through the proxy with [headers] on every request, and returns its loopback address. Give
     * [contentLength] when the stream's length is known (the source's `contentLength`), so a `HEAD` or the first answer needs no
     * upstream request to know it.
     */
    fun register(upstreamUrl: String, headers: Map<String, String>, contentLength: Long? = null): String {
        val current = session
        val stream = Stream(
            url = upstreamUrl,
            headers = headers,
            googlevideo = GooglevideoRequests.isVideoplayback(upstreamUrl),
            knownLength = contentLength?.takeIf { it > 0 } ?: clenOf(upstreamUrl),
        )
        val n = synchronized(current) {
            current.streams += stream
            current.streams.size - 1
        }
        return "http://127.0.0.1:$port/${current.token}/$n"
    }

    /** Forgets every registered stream and starts a session with a fresh token, which it returns. Readers of the old one stop. */
    fun reset(): String {
        val fresh = Session(newToken())
        session = fresh
        networkFailed = false
        calls.forEach { it.cancel() } // the old session's requests: the new one has made none yet
        return fresh.token
    }

    /** Stops the server and every upstream request; the proxy is not used again. */
    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
        calls.forEach { it.cancel() }
        executor.shutdownNow()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    // ---- serving ------------------------------------------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        try {
            serve(exchange)
        } catch (_: ReaderGone) {
            // mpv closed the connection (a seek, a new load): nothing to answer
        } catch (e: IOException) {
            if (!closed) System.err.println("Cosmotune video proxy: ${e.message}")
        } catch (e: RuntimeException) {
            System.err.println("Cosmotune video proxy: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            exchange.close()
        }
    }

    private fun serve(exchange: HttpExchange) {
        val head = exchange.requestMethod.equals("HEAD", ignoreCase = true)
        if (!head && !exchange.requestMethod.equals("GET", ignoreCase = true)) return reply(exchange, 405)
        val current = session
        val parts = exchange.requestURI.rawPath.trim('/').split('/')
        if (parts.size != 2 || parts[0] != current.token) return reply(exchange, 404)
        val stream = parts[1].toIntOrNull()?.let { current.streams.getOrNull(it) } ?: return reply(exchange, 404)
        val range = parseRange(exchange.requestHeaders.getFirst("Range"))

        // Where the answer starts; a suffix range needs the length first.
        if (range?.suffix != null && stream.length < 0) learnLength(current, stream)
        val start = when {
            range == null -> 0L
            range.suffix != null -> if (stream.length < 0) return reply(exchange, 416) else maxOf(0L, stream.length - range.suffix)
            else -> range.start
        }
        if (stream.length in 0..start) return unsatisfiable(exchange, stream.length)

        // The first piece is opened before the headers go out, so a 403 can still be answered as one.
        var first: Response? = null
        var firstEnd = pieceEnd(start, range?.end, stream.length)
        if (!(head && stream.length >= 0)) {
            try {
                first = open(current, stream, start, firstEnd)
            } catch (_: Expired) {
                onExpired(current.token)
                return reply(exchange, 403)
            } catch (e: Refused) {
                return reply(exchange, if (e.code == 416) 416 else 502)
            }
            if (stream.length in 0..start) {
                first.close()
                return unsatisfiable(exchange, stream.length)
            }
            firstEnd = pieceEnd(start, range?.end, stream.length)
            if (head) {
                first.close()
                first = null
            }
        }

        val headers = exchange.responseHeaders
        headers.set("Accept-Ranges", "bytes")
        headers.set("Content-Type", stream.contentType ?: "application/octet-stream")
        val length = stream.length
        val code = if (range != null) 206 else 200
        if (length >= 0) {
            val end = minOf(range?.end ?: (length - 1), length - 1)
            if (code == 206) headers.set("Content-Range", "bytes $start-$end/$length")
            if (head) {
                headers.set("Content-Length", (end - start + 1).toString())
                exchange.sendResponseHeaders(code, -1)
                return
            }
            exchange.sendResponseHeaders(code, end - start + 1)
            pump(current, stream, first, firstEnd, start, end, exchange.responseBody)
        } else {
            // No length known (an address without clen whose first piece came back whole): stream until the source runs dry.
            if (head) {
                exchange.sendResponseHeaders(code, -1)
                return
            }
            exchange.sendResponseHeaders(code, 0)
            pump(current, stream, first, firstEnd, start, -1, exchange.responseBody)
        }
    }

    /**
     * Copies the bytes [start]..[end] (inclusive; -1 for "until the source ends") to [out], piece by piece, the first piece
     * already open as [first] (ending at [firstEnd]). Stops when the session changes (a new load) or the reader leaves.
     */
    private fun pump(current: Session, stream: Stream, first: Response?, firstEnd: Long, start: Long, end: Long, out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        var position = start
        var open = first
        var openEnd = firstEnd
        var retried = false
        try {
            while (end < 0 || position <= end) {
                if (closed || current !== session) return
                val began = System.nanoTime()
                val response = open ?: try {
                    openEnd = pieceEnd(position, end.takeIf { it >= 0 }, stream.length)
                    open(current, stream, position, openEnd)
                } catch (_: Expired) {
                    onExpired(current.token)
                    return
                } catch (e: Refused) {
                    if (e.code == 416 && end < 0) return // a file of unknown length that ended on a piece boundary
                    throw e
                }
                open = null
                val want = openEnd - position + 1
                var got = 0L
                var waitingNanos = 0L
                try {
                    response.use {
                        val input = it.body.byteStream()
                        while (got < want) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), want - got).toInt())
                            if (n < 0) break
                            val writeStarted = System.nanoTime()
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                throw ReaderGone(e)
                            }
                            waitingNanos += System.nanoTime() - writeStarted
                            got += n
                        }
                    }
                } catch (e: ReaderGone) {
                    throw e
                } catch (e: IOException) {
                    // The source dropped the connection: try the rest of this piece once more, then give up.
                    position += got
                    if (closed || current !== session) return // stopped on purpose
                    networkFailed = true
                    if (retried) throw e
                    retried = true
                    continue
                }
                measure(got, System.nanoTime() - began - waitingNanos)
                position += got
                if (got < want) {
                    if (end < 0) return // the end of a file whose length nobody told
                    if (retried) throw IOException("the source ended at $position of $end")
                    retried = true
                } else {
                    retried = false
                }
            }
            out.flush()
        } finally {
            open?.close()
        }
    }

    /** Opens a piece at 0 only to learn the stream's length (for a suffix range on a stream whose length nobody told). */
    private fun learnLength(current: Session, stream: Stream) {
        try {
            open(current, stream, 0, GooglevideoRequests.CHUNK_BYTES - 1).close()
        } catch (_: Expired) {
            onExpired(current.token)
        } catch (_: Refused) {
        }
    }

    /**
     * Opens the source's bytes [start]..[end] of [stream] and returns the answer positioned at [start]: a POST with `&range=`
     * and `&rn=` for a googlevideo address (until it refuses one with 400 or 405), else a GET with a `Range` header. Learns the
     * content type and, when it can, the length.
     */
    private fun open(current: Session, stream: Stream, start: Long, end: Long): Response {
        if (stream.googlevideo && !stream.useGet) {
            val url = GooglevideoRequests.withRequestNumber(
                GooglevideoRequests.withRange(stream.url, start, end),
                stream.requestNumber.getAndIncrement(),
            )
            val request = Request.Builder().url(url).headers(stream.headers.toHeaders()).header("Accept-Encoding", "identity")
                .post(GooglevideoRequests.POST_BODY.toRequestBody(null)).build()
            val response = execute(request)
            when (response.code) {
                200, 206 -> {
                    learn(stream, response, start, end, wholeFileIf200 = false)
                    return response
                }
                400, 405 -> {
                    response.close()
                    stream.useGet = true
                }
                403 -> {
                    response.close()
                    throw Expired()
                }
                else -> {
                    response.close()
                    throw Refused(response.code)
                }
            }
        }
        val url = if (stream.googlevideo) GooglevideoRequests.withRequestNumber(stream.url, stream.requestNumber.getAndIncrement()) else stream.url
        val request = Request.Builder().url(url).headers(stream.headers.toHeaders()).header("Accept-Encoding", "identity")
            .header("Range", "bytes=$start-$end").get().build()
        val response = execute(request)
        when (response.code) {
            206 -> {
                learn(stream, response, start, end, wholeFileIf200 = false)
                return response
            }
            200 -> {
                // The source ignored the range and sends the whole file: skip to the start.
                learn(stream, response, start, end, wholeFileIf200 = true)
                if (start > 0) response.body.source().skip(start)
                return response
            }
            403 -> {
                response.close()
                throw Expired()
            }
            else -> {
                response.close()
                throw Refused(response.code)
            }
        }
    }

    private fun execute(request: Request): Response {
        val call = client.newCall(request)
        calls += call
        try {
            return call.execute()
        } catch (e: IOException) {
            if (!closed && !call.isCanceled()) networkFailed = true
            throw e
        } finally {
            calls -= call
        }
    }

    private fun learn(stream: Stream, response: Response, start: Long, end: Long, wholeFileIf200: Boolean) {
        if (stream.contentType == null) stream.contentType = response.header("Content-Type")
        if (stream.length >= 0) return
        val total = response.header("Content-Range")?.substringAfterLast('/', "")?.toLongOrNull()
        if (total != null && total > 0) {
            stream.length = total
            return
        }
        val count = response.body.contentLength()
        if (count < 0) return
        if (response.code == 200 && wholeFileIf200) stream.length = count
        else if (count < end - start + 1) stream.length = start + count
    }

    private fun measure(bytes: Long, nanos: Long) {
        if (bytes < MEASURE_MIN_BYTES || nanos <= 0) return
        val kbps = bytes * 8.0 / 1000.0 / (nanos / 1e9)
        val previous = bandwidth
        bandwidth = if (previous <= 0) kbps else previous * (1 - SMOOTHING) + kbps * SMOOTHING
        onBandwidth(bandwidth.toInt())
    }

    private fun reply(exchange: HttpExchange, code: Int) {
        exchange.sendResponseHeaders(code, -1)
    }

    private fun unsatisfiable(exchange: HttpExchange, length: Long) {
        exchange.responseHeaders.set("Content-Range", "bytes */$length")
        exchange.sendResponseHeaders(416, -1)
    }

    /** A request's byte range: [start] to [end] (inclusive, null for "to the end"), or the last [suffix] bytes. */
    private class ByteRangeRequest(val start: Long, val end: Long?, val suffix: Long?)

    companion object {
        /** The server's threads: two streams (video and audio) and their reconnections after a seek. */
        const val THREADS = 4
        private const val BACKLOG = 16
        private const val COPY_BUFFER = 64 * 1024
        private const val MEASURE_MIN_BYTES = 256 * 1024L
        private const val SMOOTHING = 0.3

        /** Video's client for the source: no cookie jar, timeouts that let a slow piece finish. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** The `clen` (content length) parameter of a googlevideo address, or null. */
        internal fun clenOf(url: String): Long? =
            url.substringBefore('#').substringAfter('?', "").split('&')
                .firstOrNull { it.substringBefore('=') == "clen" }?.substringAfter('=', "")?.toLongOrNull()?.takeIf { it > 0 }

        /** The end of the piece that starts at [position]: 2 MiB on, capped by the [requestedEnd] and the [length] when known. */
        private fun pieceEnd(position: Long, requestedEnd: Long?, length: Long): Long {
            var end = position + GooglevideoRequests.CHUNK_BYTES - 1
            if (requestedEnd != null) end = minOf(end, requestedEnd)
            if (length >= 0) end = minOf(end, length - 1)
            return end
        }

        /** `bytes=a-b`, `bytes=a-` or `bytes=-n`; anything else (or several ranges) is no range at all, as HTTP allows. */
        private fun parseRange(header: String?): ByteRangeRequest? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
            if (',' in spec) return null
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                val suffix = to.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRangeRequest(0, null, suffix)
            }
            val start = from.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val end = if (to.isEmpty()) null else to.toLongOrNull()?.takeIf { it >= start } ?: return null
            return ByteRangeRequest(start, end, null)
        }
    }
}
