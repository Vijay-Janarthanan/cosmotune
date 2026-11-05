package com.cosmotune.providers

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Loopback byte transport keeps remote provider addresses/headers behind the provider network boundary. */
internal class ProviderMediaProxy : AutoCloseable {
    /** Either a remote address fetched through [network], or a prepared local [file] released with the lease. */
    private data class Lease(val provider: String, val remote: String?, val headers: JsonObject, val network: ProviderNetwork?,
        val file: java.io.File? = null, val release: AutoCloseable? = null,
        val created: Long = System.nanoTime(), var active: Int = 0,
        val clients: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()) {
        fun dispose() { network?.cancel(); release?.let { runCatching { it.close() } } }
    }
    private val lock = Any()
    private val leases = LinkedHashMap<String, Lease>()
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "Provider media").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var closed = false
    private val listener = Thread({
        try {
            while (!closed) {
                val socket = server.accept().apply { soTimeout = 10_000; sendBufferSize = 64 * 1024 }
                sockets.add(socket)
                try { workers.execute { try { socket.use { serve(it) } } finally { sockets.remove(socket) } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { socket.close(); sockets.remove(socket) }
            }
        } catch (_: java.io.IOException) { if (!closed) close() }
    }, "Provider media listener").apply { isDaemon = true; start() }

    fun register(provider: String, remote: String, headers: JsonObject, network: ProviderNetwork): String {
        network.address(remote)
        return add(Lease(provider, remote, headers, network))
    }

    /**
     * Serves a complete file a provider prepared in its own workspace (the SDK's file-only `download`
     * route). [release] is closed with the lease (revoke, expiry, eviction or shutdown), which deletes
     * the disposable copy; the decoder only ever sees the loopback address, never the workspace path.
     */
    fun registerFile(provider: String, file: java.io.File, release: AutoCloseable): String {
        require(file.isFile && file.length() in 1..MAX_MEDIA_BYTES) { "Provider returned no complete media file" }
        return add(Lease(provider, null, JsonObject(emptyMap()), null, file, release))
    }

    private fun add(lease: Lease): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        val dropped = mutableListOf<Lease>()
        try {
            synchronized(lock) {
                check(!closed)
                leases.entries.filter { elapsed(it.value) > MAX_AGE_MS && it.value.active == 0 }.map { it.key }.forEach { leases.remove(it)?.let(dropped::add) }
                while (leases.size >= 64) {
                    val victim = leases.entries.firstOrNull { it.value.active == 0 } ?: error("Provider media capacity is busy")
                    leases.remove(victim.key)?.let(dropped::add)
                }
                leases[token] = lease
            }
        } finally { dropped.forEach(Lease::dispose) }
        return "http://127.0.0.1:${server.localPort}/$token/media"
    }

    fun revoke(provider: String) {
        val removed = synchronized(lock) { leases.filterValues { it.provider == provider }.keys.mapNotNull(leases::remove) }
        removed.forEach { lease ->
            // Cancelling upstream cannot interrupt a write blocked on a paused local reader.
            lease.clients.forEach { runCatching { it.close() } }
            lease.dispose()
        }
    }

    private fun serve(socket: Socket) {
        var lease: Lease? = null
        var sent = false
        var deadline = deadlines.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
        try {
            val input = BufferedInputStream(socket.getInputStream())
            var headerBytes = 0
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    require(byte >= 0 && ++headerBytes <= 16_384)
                    if (byte == 10) break
                    require(bytes.size() < 8_192)
                    bytes.write(byte)
                }
                return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            }
            val request = line().split(' ')
            require(request.size == 3 && request[0] in setOf("GET", "HEAD") && request[2] in setOf("HTTP/1.1", "HTTP/1.0"))
            val match = PATH.matchEntire(request[1]) ?: error("Unknown media route")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val next = line()
                if (next.isEmpty()) break
                val name = next.substringBefore(':').lowercase(java.util.Locale.ROOT)
                require(':' in next && name !in headers && name.matches(Regex("[a-z0-9-]+")))
                headers[name] = next.substringAfter(':').trim()
            }
            require(headers["host"] == "127.0.0.1:${server.localPort}")
            require(headers["transfer-encoding"] == null && (headers["content-length"] == null || headers["content-length"] == "0"))
            val held = synchronized(lock) {
                leases[match.groupValues[1]]?.takeIf { elapsed(it) <= MAX_AGE_MS }?.also { it.active++; it.clients.add(socket) }
            } ?: error("Media lease expired")
            lease = held
            deadline.cancel(false)
            deadline = deadlines.schedule({ runCatching { socket.close() } }, 5, TimeUnit.MINUTES)
            if (held.file != null) {
                serveFile(socket, request[0], held.file, headers["range"]) { sent = true }
                return
            }
            val network = requireNotNull(held.network); val remote = requireNotNull(held.remote)
            val options = buildJsonObject {
                put("method", request[0])
                putJsonObject("headers") {
                    held.headers.forEach { (name, value) -> if (!name.equals("range", true) && !name.equals("if-range", true)) put(name, value) }
                    headers["range"]?.let { range -> require(RANGE.matches(range)); put("Range", range) }
                    headers["if-range"]?.let { require(it.length <= 8_192); put("If-Range", it) }
                }
            }
            network.withResponse(remote, options, media = true) { response ->
                require(response.code in setOf(200, 206, 416)) { "Provider did not return media" }
                val size = response.body.contentLength()
                require(size <= MAX_MEDIA_BYTES)
                val output = BufferedOutputStream(socket.getOutputStream())
                val status = when (response.code) { 206 -> "206 Partial Content"; 416 -> "416 Range Not Satisfiable"; else -> "200 OK" }
                val head = buildString {
                    append("HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: private, no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; sandbox\r\n")
                    if (size >= 0) append("Content-Length: $size\r\n")
                    for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) response.header(name)?.let { value ->
                        require(value.length <= 8_192 && '\r' !in value && '\n' !in value)
                        append("$name: $value\r\n")
                    }
                    append("\r\n")
                }
                output.write(head.toByteArray(Charsets.US_ASCII)); output.flush(); sent = true
                if (request[0] != "HEAD") response.body.byteStream().use { remote ->
                    val bytes = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val count = remote.read(bytes)
                        if (count < 0) break
                        require(written <= MAX_MEDIA_BYTES - count)
                        output.write(bytes, 0, count); output.flush(); written += count
                    }
                }
            }
        } catch (_: Exception) {
            if (!sent) runCatching { socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        } finally { deadline.cancel(false); lease?.let { synchronized(lock) { it.active--; it.clients.remove(socket) } } }
    }

    override fun close() {
        val old = synchronized(lock) { if (closed) return; closed = true; leases.values.toList().also { leases.clear() } }
        runCatching { server.close() }
        old.forEach(Lease::dispose)
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow()
    }
    /** Byte ranges over a prepared file, with the same response hardening as relayed media. */
    private fun serveFile(socket: Socket, method: String, file: java.io.File, range: String?, onHead: () -> Unit) {
        val size = file.length()
        var start = 0L; var end = size - 1; var status = "200 OK"
        if (range != null) {
            require(RANGE.matches(range))
            val spec = range.removePrefix("bytes=")
            val first = spec.substringBefore('-'); val last = spec.substringAfter('-')
            if (first.isEmpty()) start = (size - last.toLong()).coerceAtLeast(0)
            else { start = first.toLong(); if (last.isNotEmpty()) end = minOf(last.toLong(), size - 1) }
            status = if (start >= size || start > end) "416 Range Not Satisfiable" else "206 Partial Content"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        val satisfiable = !status.startsWith("416")
        val length = if (satisfiable) end - start + 1 else 0L
        val head = buildString {
            append("HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: private, no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; sandbox\r\n")
            append("Accept-Ranges: bytes\r\nContent-Type: ${contentType(file)}\r\nContent-Length: $length\r\n")
            if (status.startsWith("206")) append("Content-Range: bytes $start-$end/$size\r\n")
            if (!satisfiable) append("Content-Range: bytes */$size\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII)); output.flush(); onHead()
        if (method == "HEAD" || !satisfiable) return
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val bytes = ByteArray(64 * 1024); var remaining = length
            while (remaining > 0) {
                val count = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                if (count < 0) break
                output.write(bytes, 0, count); output.flush(); remaining -= count
            }
        }
    }
    private fun contentType(file: java.io.File): String = when (runCatching { ProviderPreparedMedia.container(file) }.getOrNull()) {
        "flac" -> "audio/flac"; "mp4" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "aiff" -> "audio/aiff"
        else -> "application/octet-stream"
    }
    private fun elapsed(lease: Lease) = (System.nanoTime() - lease.created) / 1_000_000
    private companion object {
        val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1,
            { task -> Thread(task, "Provider media deadlines").apply { isDaemon = true } }).apply { removeOnCancelPolicy = true }
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        const val MAX_MEDIA_BYTES = 512L * 1024 * 1024
        val PATH = Regex("/([a-f0-9]{32})/media")
        val RANGE = Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")
    }
}
