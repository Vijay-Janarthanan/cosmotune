package com.cosmotune.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The player's repeat setting, in the order its own button cycles. */
@Serializable enum class RemoteRepeat { OFF, ALL, ONE }

/**
 * What the page shows. [shuffle], [repeat] and [liked] are null when the host
 * does not offer that control (or, for [liked], when the playing track cannot
 * be loved), and the page then hides it.
 */
@Serializable data class RemotePlayback(
    val title: String = "Nothing playing", val artist: String = "", val playing: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long? = null, val volume: Float? = null,
    val shuffle: Boolean? = null, val repeat: RemoteRepeat? = null, val liked: Boolean? = null,
)
@Serializable data class RemoteTrack(val id: String, val title: String, val artist: String)

/** One Up Next row. [index] is its absolute place in the host's queue, what [RemoteAction.PLAY_QUEUE_INDEX] names. */
@Serializable data class RemoteQueueItem(val index: Int, val title: String, val artist: String)
@Serializable data class RemoteQueue(val current: Int = -1, val items: List<RemoteQueueItem> = emptyList())

@Serializable enum class RemoteAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK, VOLUME, PLAY_TRACK, SHUFFLE, REPEAT, LIKE, PLAY_QUEUE_INDEX }
@Serializable data class RemoteCommand(
    val action: RemoteAction, val positionMs: Long? = null, val volume: Float? = null, val trackId: String? = null,
    val index: Int? = null,
)

/** Native hosts marshal commands to their player thread and expose metadata, never account cookies or stream URLs. */
interface LanRemoteHost {
    fun playback(): RemotePlayback
    suspend fun command(command: RemoteCommand)
    suspend fun search(query: String): List<RemoteTrack> = emptyList()

    /** The queue around the playing track; empty when the host does not offer Up Next. */
    suspend fun queue(): RemoteQueue = RemoteQueue()
}

/** The repeat setting after the player's own button: off, all, one, off. */
fun RemoteRepeat.next(): RemoteRepeat = when (this) {
    RemoteRepeat.OFF -> RemoteRepeat.ALL
    RemoteRepeat.ALL -> RemoteRepeat.ONE
    RemoteRepeat.ONE -> RemoteRepeat.OFF
}

/** Token is deliberately absent from toString/logging. Pairing links carry it in the URL fragment. */
class LanRemoteSession internal constructor(val origin: String, private val key: String) {
    val pairingUrl: String get() = "$origin/#key=$key"
    override fun toString() = "LanRemoteSession($origin, paired link hidden)"
}

/**
 * An explicitly enabled, bounded LAN endpoint. One ephemeral key per start,
 * no saved cookies/tokens, no external listener and no background auto-start.
 */
class LanRemoteServer private constructor(private val host: LanRemoteHost, address: InetAddress, port: Int) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lifetime = SupervisorJob()
    private val commandMutex = Mutex()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val knownTracks = LinkedHashMap<String, RemoteTrack>()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val listener = ServerSocket(port, 16, address)
    val session = LanRemoteSession("http://${address.hostAddress}:${listener.localPort}", key)
    private val expectedAuthority = "${address.hostAddress}:${listener.localPort}"
    private val deadlines = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "cosmotune-lan-deadline").apply { isDaemon = true } }
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { action -> Thread(action, "cosmotune-lan-request").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val acceptor = thread(name = "cosmotune-lan-accept", isDaemon = true) {
        while (!closed.get()) try {
            val socket = listener.accept()
            socket.soTimeout = 2500
            sockets.add(socket)
            if (closed.get()) { sockets.remove(socket); socket.close(); continue }
            try { workers.execute { serve(socket) } }
            catch (_: Exception) { sockets.remove(socket); socket.close() }
        } catch (_: Exception) { if (closed.get()) break }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class BadRequest : Exception()

    /**
     * After the answer: stop sending, and read away what the browser may still be sending (a few KiB, for a moment), so the
     * connection closes in order. Closing with unread data makes some systems (macOS) reset the connection, which can throw the
     * answer away before the browser has read it.
     */
    private fun settle(socket: Socket) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = 200
            val sink = ByteArray(1024)
            var left = 16 * 1024
            val input = socket.getInputStream()
            while (left > 0) { val n = input.read(sink); if (n < 0) break; left -= n }
        }
    }

    private fun serve(socket: Socket) {
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            timeout = deadlines.schedule({ runCatching { socket.close() } }, 8, TimeUnit.SECONDS)
            socket.use {
                try {
                val request = try { read(socket) } catch (_: Exception) { respond(socket, 400, "text/plain", "Invalid request"); return }
                if (request.headers["host"]?.lowercase(Locale.ROOT) != expectedAuthority.lowercase(Locale.ROOT)) {
                    respond(socket, 403, "text/plain", "Invalid host"); return
                }
                val origin = request.headers["origin"]
                if (origin != null && origin != session.origin) { respond(socket, 403, "text/plain", "Origin rejected"); return }
                if (request.method == "GET" && request.path == "/") {
                    respond(socket, 200, "text/html; charset=utf-8", LanRemotePage.html); return
                }
                val supplied = request.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
                if (closed.get() || supplied.length != key.length || !MessageDigest.isEqual(supplied.toByteArray(), key.toByteArray())) {
                    respond(socket, 401, "text/plain", "Pair this browser using the link shown in Cosmotune"); return
                }
                try {
                    runBlocking(lifetime) { withTimeout(5000) { route(socket, request) } }
                } catch (_: BadRequest) { respond(socket, 400, "text/plain", "Invalid command") }
                catch (_: Exception) { respond(socket, 503, "text/plain", "The player is unavailable. Try again.") }
                } finally { settle(socket) }
            }
        } catch (_: Exception) { /* A closed browser is a completed request. */ }
        finally { timeout?.cancel(false); sockets.remove(socket); runCatching { socket.close() } }
    }

    private suspend fun route(socket: Socket, request: Request) {
        when {
            request.method == "GET" && request.path == "/api/status" -> {
                val state = host.playback().let { value -> value.copy(title = value.title.take(1000), artist = value.artist.take(1000),
                    positionMs = value.positionMs.coerceIn(0, MAX_POSITION), durationMs = value.durationMs?.coerceIn(0, MAX_POSITION),
                    volume = value.volume?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)) }
                respond(socket, 200, "application/json", json.encodeToString(RemotePlayback.serializer(), state))
            }
            request.method == "GET" && (request.path == "/api/search" || request.path.startsWith("/api/search?q=")) -> {
                val query = if (request.path == "/api/search") "" else try {
                    URLDecoder.decode(request.path.substringAfter("?q="), "UTF-8").also { if (it.length > 300) throw BadRequest() }
                } catch (_: Exception) { throw BadRequest() }
                val tracks = host.search(query).take(50).map { track ->
                    if (track.id.isBlank() || track.id.length > 4096) throw BadRequest()
                    track.copy(title = track.title.take(500), artist = track.artist.take(500))
                }
                val visible = synchronized(knownTracks) {
                    val mapped = tracks.map { original ->
                        val token = java.util.UUID.randomUUID().toString()
                        knownTracks[token] = original
                        original.copy(id = token)
                    }
                    while (knownTracks.size > 500) knownTracks.remove(knownTracks.keys.first())
                    mapped
                }
                val array = kotlinx.serialization.builtins.ListSerializer(RemoteTrack.serializer())
                respond(socket, 200, "application/json", json.encodeToString(array, visible))
            }
            request.method == "GET" && request.path == "/api/queue" -> {
                val queue = host.queue()
                val items = queue.items.take(MAX_QUEUE_ROWS).map { item ->
                    if (item.index !in 0..MAX_QUEUE_INDEX) throw BadRequest()
                    item.copy(title = item.title.take(500), artist = item.artist.take(500))
                }
                val current = queue.current.takeIf { it in 0..MAX_QUEUE_INDEX } ?: -1
                respond(socket, 200, "application/json", json.encodeToString(RemoteQueue.serializer(), RemoteQueue(current, items)))
            }
            request.method == "POST" && request.path == "/api/action" -> {
                if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json") throw BadRequest()
                val command = try { json.decodeFromString<RemoteCommand>(request.body.toString(Charsets.UTF_8)) } catch (_: Exception) { throw BadRequest() }
                if (command.action != RemoteAction.PLAY_QUEUE_INDEX && command.index != null) throw BadRequest()
                when (command.action) {
                    RemoteAction.SEEK -> if (command.positionMs == null || command.positionMs !in 0..MAX_POSITION || command.volume != null || command.trackId != null) throw BadRequest()
                    RemoteAction.VOLUME -> if (command.volume == null || !command.volume.isFinite() || command.volume !in 0f..1f || command.positionMs != null || command.trackId != null) throw BadRequest()
                    RemoteAction.PLAY_TRACK -> if (command.trackId == null || synchronized(knownTracks) { command.trackId !in knownTracks } || command.positionMs != null || command.volume != null) throw BadRequest()
                    RemoteAction.PLAY_QUEUE_INDEX -> if (command.index == null || command.index !in 0..MAX_QUEUE_INDEX || command.trackId != null || command.positionMs != null || command.volume != null) throw BadRequest()
                    else -> if (command.trackId != null || command.positionMs != null || command.volume != null) throw BadRequest()
                }
                commandMutex.withLock {
                    val nativeCommand = if (command.action == RemoteAction.PLAY_TRACK) {
                        val track = synchronized(knownTracks) { knownTracks[command.trackId] } ?: throw BadRequest()
                        command.copy(trackId = track.id)
                    } else command
                    host.command(nativeCommand)
                }
                respond(socket, 200, "application/json", "{\"ok\":true}")
            }
            request.path.substringBefore('?') in setOf("/api/action", "/api/status", "/api/search", "/api/queue") -> respond(socket, 405, "text/plain", "Method not allowed")
            else -> respond(socket, 404, "text/plain", "Not found")
        }
    }

    private fun read(socket: Socket): Request {
        val input = socket.getInputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var total = 0
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                if (++total > 8192 || System.nanoTime() > deadline) throw BadRequest()
                val value = input.read()
                if (value < 0) throw BadRequest()
                if (value == 13) {
                    if (input.read() != 10 || ++total > 8192) throw BadRequest()
                    return out.toString("US-ASCII")
                }
                if (value < 32 || value > 126) throw BadRequest()
                out.write(value)
            }
        }
        val first = line().split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1" || first[0] !in setOf("GET", "POST") ||
            first[1].length > 2048 || !first[1].startsWith('/') || first[1].startsWith("//") || '#' in first[1]) throw BadRequest()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index < 1 || headers.size >= 32) throw BadRequest()
            val name = line.substring(0, index).lowercase(Locale.ROOT)
            if (!name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } || headers.put(name, line.substring(index + 1).trim()) != null) throw BadRequest()
        }
        if ("transfer-encoding" in headers || "expect" in headers) throw BadRequest()
        val length = headers["content-length"]?.toIntOrNull() ?: if ("content-length" in headers) throw BadRequest() else 0
        if (length !in 0..8192 || first[0] == "GET" && length != 0) throw BadRequest()
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            if (System.nanoTime() > deadline) throw BadRequest()
            val read = input.read(body, offset, body.size - offset)
            if (read <= 0) throw BadRequest()
            offset += read
        }
        return Request(first[0], first[1], headers, body)
    }

    private fun respond(socket: Socket, status: Int, type: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Service Unavailable" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        lifetime.cancel()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        deadlines.shutdownNow()
        synchronized(knownTracks) { knownTracks.clear() }
        if (Thread.currentThread() !== acceptor) acceptor.join(1000)
    }

    companion object {
        private const val MAX_POSITION = 7L * 24 * 60 * 60 * 1000
        private const val MAX_QUEUE_ROWS = 100
        private const val MAX_QUEUE_INDEX = 1_000_000
        fun start(host: LanRemoteHost, address: InetAddress = InetAddress.getByName("127.0.0.1"), port: Int = 0): LanRemoteServer {
            require(port in 0..65535)
            require(address is Inet4Address && !address.isAnyLocalAddress && (address.isLoopbackAddress || address.isSiteLocalAddress)) { "Select a local IPv4 interface" }
            require(NetworkInterface.getByInetAddress(address)?.isUp == true) { "The selected network interface is unavailable" }
            return LanRemoteServer(host, address, port)
        }
        fun localAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isAnyLocalAddress && (it.isLoopbackAddress || it.isSiteLocalAddress) }
            .distinctBy { it.hostAddress }.sortedBy { it.isLoopbackAddress }
    }
}
