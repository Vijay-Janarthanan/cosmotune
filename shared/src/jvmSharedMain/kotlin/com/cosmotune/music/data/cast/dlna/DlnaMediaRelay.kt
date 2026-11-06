package com.cosmotune.music.data.cast.dlna

import com.cosmotune.music.data.Http
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Serves the song a renderer is asked to play, on this device's local address.
 *
 * A TV cannot play YouTube's media URLs itself: they answer only requests dressed as the client that minted them and,
 * asked for a whole file, refuse with 403. So the renderer is given `http://<this device>:<port>/m/<token>` instead, and
 * each of its requests is passed on upstream with the right headers, a window of [WINDOW_BYTES] at a time, honouring the
 * renderer's own `Range`. A file on this device (the desktop's local library) is served straight from disk.
 *
 * Bound to all addresses but answering only requests from local-network addresses, and only for tokens it handed out.
 */
class DlnaMediaRelay(private val windowBytes: Long = WINDOW_BYTES) {

    /** Where a token's bytes come from. */
    sealed interface Source {
        val mimeType: String
        data class Remote(val url: String, val headers: Map<String, String>, override val mimeType: String) : Source
        data class LocalFile(val file: File, override val mimeType: String) : Source
    }

    private val sources = ConcurrentHashMap<String, Source>()
    @Volatile private var server: ServerSocket? = null

    /** The URL a renderer at [rendererHost] can fetch [source] from. Starts the server on first use. */
    fun publish(source: Source, rendererHost: String): String {
        val socket = ensureServer()
        val token = UUID.randomUUID().toString().replace("-", "")
        // Only the last few songs need serving; a renderer that comes back for an old token gets a 404.
        if (sources.size > 8) sources.keys.take(sources.size - 8).forEach(sources::remove)
        sources[token] = source
        val host = localAddressFacing(rendererHost)
        val printed = if (':' in host) "[$host]" else host
        return "http://$printed:${socket.localPort}/m/$token"
    }

    fun close() {
        runCatching { server?.close() }
        server = null
        sources.clear()
    }

    @Synchronized
    private fun ensureServer(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        server = socket
        thread(name = "dlna-relay", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "dlna-relay-client", isDaemon = true) { client.use(::serve) }
            }
        }
        return socket
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val output = BufferedOutputStream(client.getOutputStream())
        try {
            answer(client, output)
        } finally {
            runCatching { output.flush() }
        }
    }

    private fun answer(client: Socket, output: OutputStream) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val remote = client.inetAddress.hostAddress.orEmpty()
        if (!com.cosmotune.music.data.NetworkPolicy.isLocalHost(remote)) return status(output, 403, "Forbidden")
        if (method != "GET" && method != "HEAD") return status(output, 405, "Method Not Allowed")
        val source = path.removePrefix("/m/").takeIf { path.startsWith("/m/") }?.let(sources::get)
            ?: return status(output, 404, "Not Found")
        runCatching { send(source, method == "HEAD", headers["range"], output) }
    }

    private fun send(source: Source, headOnly: Boolean, range: String?, output: OutputStream) {
        val total = when (source) {
            is Source.LocalFile -> source.file.length()
            is Source.Remote -> remoteLength(source) ?: return status(output, 502, "Bad Gateway")
        }
        val (from, to) = parseRange(range, total) ?: return status(output, 416, "Range Not Satisfiable", "Content-Range: bytes */$total")
        val partial = range != null
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(source.mimeType).append("\r\n")
            append("Content-Length: ").append(to - from + 1).append("\r\n")
            if (partial) append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(total).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (headOnly) return
        when (source) {
            is Source.LocalFile -> source.file.inputStream().use { stream ->
                stream.skip(from)
                copy(stream, output, to - from + 1)
            }
            is Source.Remote -> {
                var at = from
                while (at <= to) {
                    val end = minOf(to, at + windowBytes - 1)
                    val request = Request.Builder().url(source.url).apply {
                        source.headers.forEach { (name, value) -> header(name, value) }
                        header("Range", "bytes=$at-$end")
                    }.build()
                    Http.client.newCall(request).execute().use { response ->
                        val body = response.body ?: return
                        if (!response.isSuccessful) return
                        val copied = copy(body.byteStream(), output, end - at + 1)
                        if (copied <= 0) return
                        at += copied
                    }
                }
            }
        }
    }

    private fun remoteLength(source: Source.Remote): Long? = runCatching {
        val request = Request.Builder().url(source.url).apply {
            source.headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=0-0")
        }.build()
        Http.client.newCall(request).execute().use { response ->
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
        }
    }.getOrNull()

    private fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var left = limit
        var copied = 0L
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            left -= read
            copied += read
        }
        return copied
    }

    private fun status(output: OutputStream, code: Int, text: String, extra: String? = null) {
        val head = "HTTP/1.1 $code $text\r\n" + (extra?.let { "$it\r\n" } ?: "") + "Content-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8_192) return null
            line.append(c.toChar())
        }
    }

    companion object {
        /** What googlevideo hands over without a fuss in one request. */
        const val WINDOW_BYTES = 1L shl 20

        /** `bytes=a-b`, `bytes=a-` or `bytes=-n` within [total], as an inclusive pair; the whole file without a range. */
        fun parseRange(range: String?, total: Long): Pair<Long, Long>? {
            if (total <= 0) return null
            if (range == null) return 0L to total - 1
            val spec = range.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val start = spec.substring(0, dash).trim()
            val end = spec.substring(dash + 1).trim()
            return when {
                start.isEmpty() -> {
                    val suffix = end.toLongOrNull() ?: return null
                    (total - suffix).coerceAtLeast(0) to total - 1
                }
                else -> {
                    val from = start.toLongOrNull() ?: return null
                    if (from >= total) return null
                    val to = end.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                    if (to < from) null else from to to
                }
            }
        }

        /** This device's address on the network the renderer is on (no packet is sent to find it). */
        fun localAddressFacing(rendererHost: String): String = runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(rendererHost), 9)
                socket.localAddress.hostAddress
            }
        }.getOrNull()?.takeIf { it != "0.0.0.0" && it != "::" } ?: InetAddress.getLocalHost().hostAddress

        fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
    }
}
