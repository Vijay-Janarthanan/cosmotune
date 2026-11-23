package com.cosmotune.music.desktop

import com.cosmotune.podio.IcyStreamSplitter
import com.cosmotune.podio.PodioLiveTitle
import com.cosmotune.podio.PodioRecorder
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A Podio station's stream read by the app itself rather than by FFmpeg's own HTTP, so the bytes the player receives can also go
 * to a recording ([PodioRecorder.tap]) with no second connection. It asks for the stream's song titles, takes them out before
 * the decoder sees the audio and hands them to [PodioLiveTitle]. A live stream has no length and cannot seek.
 *
 * A connection that drops, or a station that hangs up, is opened again in place a few times ([RETRY_DELAYS_MS]) before the read
 * fails, so a short drop is a short gap rather than the end of the station. A station this reader could not play is remembered
 * ([refuse]) and goes back to FFmpeg's own reader.
 */
internal class DesktopLiveStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = sharedClient,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val onTitle: (String) -> Unit = { PodioLiveTitle.publish(url, it) },
) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var splitter: IcyStreamSplitter? = null
    private var tap: PodioRecorder.Tap? = null
    private val raw = ByteArray(16 * 1024)
    private val pending = java.io.ByteArrayOutputStream()
    private var pendingAt = 0
    private var pendingBytes = ByteArray(0)
    private var position = 0L
    @Volatile private var closed = false
    private var openedAtMs = 0L

    /** How many times the stream was opened again after a drop. */
    @Volatile var reconnects = 0
        private set

    /** Opens the stream; throws when the station does not answer with audio. */
    fun open() {
        openedAtMs = System.currentTimeMillis()
        connect()
    }

    private fun connect() {
        val request = Request.Builder().url(url).header("Icy-MetaData", "1").apply {
            headers.forEach { (name, value) -> if (!name.equals("Icy-MetaData", true)) header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)")
        }.build()
        val reply = client.newCall(request).execute()
        if (!reply.isSuccessful) { reply.close(); throw DesktopStreamException("station answered HTTP ${reply.code}", httpStatus = reply.code) }
        // A playlist or a web page is not audio: FFmpeg's own reader knows those.
        if (!audioType(reply.header("Content-Type"))) { reply.close(); throw DesktopStreamException("station sent ${reply.header("Content-Type")}, not audio") }
        val interval = reply.header("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_536 }
        response = reply
        input = requireNotNull(reply.body).byteStream()
        splitter = IcyStreamSplitter(interval)
        tap = PodioRecorder.tap(url, reply.header("Content-Type"), interval)
    }

    /** Up to [count] bytes of audio into the start of [into]; -1 once the stream is closed. */
    fun read(into: ByteArray, count: Int): Int {
        while (pendingAt >= pendingBytes.size) {
            if (closed) return -1
            val stream = input ?: return -1
            val read = try { stream.read(raw) } catch (failure: IOException) { if (closed) return -1; reconnect(failure); continue }
            if (read < 0) { if (closed) return -1; reconnect(null); continue }
            tap?.let { runCatching { it.write(raw, 0, read) } }
            pending.reset()
            splitter!!.feed(raw, 0, read, onAudio = { bytes, offset, length -> pending.write(bytes, offset, length) }, onTitle = { title -> runCatching { onTitle(title) } })
            pendingBytes = pending.toByteArray(); pendingAt = 0
        }
        val take = minOf(count, pendingBytes.size - pendingAt)
        System.arraycopy(pendingBytes, pendingAt, into, 0, take)
        pendingAt += take
        position += take
        return take
    }

    /** The station hung up or the read failed: opens it again, a few times, then fails the read as a network failure. */
    private fun reconnect(cause: IOException?) {
        runCatching { response?.close() }; response = null; input = null
        var last: Exception? = cause
        for (delay in retryDelaysMs) {
            if (!pause(delay)) return
            try { connect(); reconnects++; return } catch (failure: Exception) { last = failure }
        }
        // Within the first seconds this reader is not for this station: the next try goes through FFmpeg's own.
        if (System.currentTimeMillis() - openedAtMs < EARLY_MS || reconnects == 0) refuse(url)
        throw DesktopStreamException("the station stopped answering: ${last?.message ?: "it hung up"}", network = true, cause = last)
    }

    private fun pause(ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (!closed && System.currentTimeMillis() < until) Thread.sleep(minOf(50L, until - System.currentTimeMillis()).coerceAtLeast(1))
        return !closed
    }

    fun position(): Long = position

    fun seek(to: Long) { if (to != position) throw DesktopStreamException("a live station cannot seek") }

    fun close() { closed = true; runCatching { response?.close() }; response = null; input = null }

    companion object {
        // The app's own client: the listener's proxy (HTTP or SOCKS, with its sign-in) and offline mode apply to stations too.
        private val sharedClient: OkHttpClient = com.cosmotune.music.data.Http.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** Waits before each new try after a drop: about 7 s in all. */
        val RETRY_DELAYS_MS = listOf(250L, 1_000L, 2_000L, 4_000L)

        /** A failure this soon after opening says the reader does not suit the station. */
        private const val EARLY_MS = 10_000L

        private val refused = ConcurrentHashMap.newKeySet<String>()

        /** Plays this station through FFmpeg's own reader from now on, while the app runs. */
        fun refuse(url: String) { refused += url }

        /** A station address the app can read itself: plain audio over HTTP, not an HLS or DASH playlist, nor one it failed on. */
        fun suits(url: String): Boolean {
            val lower = url.lowercase().substringBefore('?')
            return url !in refused && (lower.startsWith("http://") || lower.startsWith("https://")) &&
                listOf(".m3u8", ".mpd", ".m3u", ".pls", ".asx", ".xspf").none(lower::endsWith)
        }

        /** Whether a Content-Type is audio bytes rather than a playlist or a page. A station that names none is let through. */
        fun audioType(contentType: String?): Boolean {
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (type.isEmpty() || type == "application/octet-stream" || type == "application/ogg") return true
            return type.startsWith("audio/") && listOf("mpegurl", "scpls", "x-ms-asf", "xspf").none(type::contains)
        }
    }
}
