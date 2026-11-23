package com.cosmotune.music.desktop

import com.cosmotune.podio.IcyStreamSplitter
import com.cosmotune.podio.PodioRecorder
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A Podio station's stream read by the app itself rather than by FFmpeg's own HTTP, so the bytes the player receives can also go
 * to a recording ([PodioRecorder.tap]) with no second connection. It asks for the stream's song titles and takes them out before
 * the decoder sees the audio. A live stream has no length and cannot seek.
 */
internal class DesktopLiveStream(private val url: String, private val headers: Map<String, String> = emptyMap()) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var splitter: IcyStreamSplitter? = null
    private var tap: PodioRecorder.Tap? = null
    private val raw = ByteArray(16 * 1024)
    private val pending = java.io.ByteArrayOutputStream()
    private var pendingAt = 0
    private var pendingBytes = ByteArray(0)
    private var position = 0L

    /** Opens the stream; throws when the station does not answer with audio. */
    fun open() {
        val request = Request.Builder().url(url).header("Icy-MetaData", "1").apply {
            headers.forEach { (name, value) -> if (!name.equals("Icy-MetaData", true)) header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)")
        }.build()
        val reply = client.newCall(request).execute()
        if (!reply.isSuccessful) { reply.close(); throw DesktopStreamException("station answered HTTP ${reply.code}") }
        val interval = reply.header("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_536 }
        response = reply
        input = requireNotNull(reply.body).byteStream()
        splitter = IcyStreamSplitter(interval)
        tap = PodioRecorder.tap(url, reply.header("Content-Type"), interval)
    }

    /** Up to [count] bytes of audio into the start of [into]; -1 at the end of the stream. */
    fun read(into: ByteArray, count: Int): Int {
        while (pendingAt >= pendingBytes.size) {
            val stream = input ?: return -1
            val read = stream.read(raw)
            if (read < 0) return -1
            tap?.let { runCatching { it.write(raw, 0, read) } }
            pending.reset()
            splitter!!.feed(raw, 0, read, onAudio = { bytes, offset, length -> pending.write(bytes, offset, length) }, onTitle = {})
            pendingBytes = pending.toByteArray(); pendingAt = 0
        }
        val take = minOf(count, pendingBytes.size - pendingAt)
        System.arraycopy(pendingBytes, pendingAt, into, 0, take)
        pendingAt += take
        position += take
        return take
    }

    fun position(): Long = position

    fun seek(to: Long) { if (to != position) throw DesktopStreamException("a live station cannot seek") }

    fun close() { runCatching { response?.close() }; response = null; input = null }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** A station address the app can read itself: plain audio over HTTP, not an HLS or DASH playlist. */
        fun suits(url: String): Boolean {
            val lower = url.lowercase().substringBefore('?')
            return (lower.startsWith("http://") || lower.startsWith("https://")) && listOf(".m3u8", ".mpd", ".m3u", ".pls").none(lower::endsWith)
        }
    }
}
