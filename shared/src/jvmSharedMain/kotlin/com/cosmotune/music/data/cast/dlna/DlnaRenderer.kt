// Ported from Mochy Music's foss/cast/DlnaRenderer.kt (https://github.com/realidkroo/Mochy-Music, GPL-3.0, revision 8dd8f3b0),
// copyright the Mochy Music authors; adapted for Cosmotune under the same licence (a monotonic clock in place of Android's
// SystemClock, so it runs on the desktop too). See NOTICE.md.

package com.cosmotune.music.data.cast.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** What the renderer is doing, as far as its last answer said. */
data class DlnaState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float? = null,
    /** The song played to its end on the renderer (not a stop that was asked for). */
    val finished: Boolean = false,
    val failed: Boolean = false,
    /** Several polls in a row went unanswered: the TV went off or left the network. */
    val lost: Boolean = false,
)

/** The song a renderer is asked to play: [url] must be reachable from the renderer (see [DlnaMediaRelay]). */
data class DlnaMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
)

/** A DLNA renderer driven with its own SOAP calls (AVTransport and RenderingControl), polled once a second. */
class DlnaRenderer(val device: DlnaDevice, private val pollMs: Long = 1_000) {

    private val stateFlow = MutableStateFlow(DlnaState())
    val state: StateFlow<DlnaState> = stateFlow.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var polledAt = now()
    @Volatile private var sawPlaying = false
    @Volatile private var stopping = false
    private var failedPolls = 0

    /** Fails when the renderer does not answer, so a dead entry fails here rather than on the first song. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        transportState()
        device.renderingControlUrl?.let { url ->
            runCatching {
                val reply = soap(url, RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))
                tag(reply, "CurrentVolume")?.toIntOrNull()?.let { v -> stateFlow.update { it.copy(volume = v / 100f) } }
            }
        }
        io.launch {
            while (isActive) {
                poll()
                delay(pollMs)
            }
        }
        Unit
    }

    suspend fun load(media: DlnaMedia, startMs: Long = 0) = withContext(Dispatchers.IO) {
        stopping = true
        runCatching { soap(device.avTransportUrl, AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) }
        sawPlaying = false
        stateFlow.update { it.copy(buffering = true, finished = false, failed = false, positionMs = startMs, durationMs = media.durationMs) }
        try {
            soap(
                device.avTransportUrl, AV_TRANSPORT, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to didl(media)),
            )
            soap(device.avTransportUrl, AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
        } catch (e: IOException) {
            stateFlow.update { it.copy(failed = true, buffering = false, playing = false) }
            return@withContext
        } finally {
            stopping = false
        }
        // A renderer only takes a seek once it is playing, so the start waits for a poll that says so.
        if (startMs > SEEK_WORTH_MS) {
            repeat(START_SEEK_TRIES) {
                delay(pollMs)
                if (stateFlow.value.playing) {
                    runCatching { seekNow(startMs) }
                    return@withContext
                }
            }
        }
    }

    suspend fun play() = withContext(Dispatchers.IO) {
        stateFlow.update { it.copy(playing = true) }
        soap(device.avTransportUrl, AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
        Unit
    }

    suspend fun pause() = withContext(Dispatchers.IO) {
        stateFlow.update { it.copy(playing = false) }
        soap(device.avTransportUrl, AV_TRANSPORT, "Pause", listOf("InstanceID" to "0"))
        Unit
    }

    suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) { seekNow(positionMs) }

    private fun seekNow(positionMs: Long) {
        stateFlow.update { it.copy(positionMs = positionMs) }
        polledAt = now()
        soap(device.avTransportUrl, AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to clock(positionMs)))
    }

    suspend fun setVolume(volume: Float) = withContext(Dispatchers.IO) {
        val url = device.renderingControlUrl ?: return@withContext
        val level = volume.coerceIn(0f, 1f)
        stateFlow.update { it.copy(volume = level) }
        soap(url, RENDERING_CONTROL, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (level * 100).toInt().toString()))
        Unit
    }

    /** Where the renderer is now, moved on from its last answer while it plays. */
    fun positionNow(): Long {
        val s = stateFlow.value
        if (!s.playing || s.buffering) return s.positionMs
        val moved = s.positionMs + (now() - polledAt)
        return if (s.durationMs > 0) moved.coerceAtMost(s.durationMs) else moved
    }

    suspend fun close(stopPlayback: Boolean) {
        io.cancel()
        if (!stopPlayback) return
        withContext(Dispatchers.IO) {
            stopping = true
            runCatching { soap(device.avTransportUrl, AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) }
        }
    }

    private fun poll() {
        try {
            val transport = transportState()
            val position = soap(device.avTransportUrl, AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))
            failedPolls = 0
            val at = tag(position, "RelTime")?.let(::parseClock)
            val length = tag(position, "TrackDuration")?.let(::parseClock)?.takeIf { it > 0 }
            val playing = transport == "PLAYING"
            if (playing) sawPlaying = true
            polledAt = now()
            stateFlow.update { current ->
                current.copy(
                    playing = playing || transport == "TRANSITIONING",
                    buffering = transport == "TRANSITIONING",
                    positionMs = at ?: current.positionMs,
                    durationMs = length ?: current.durationMs,
                    finished = !stopping && sawPlaying && (transport == "STOPPED" || transport == "NO_MEDIA_PRESENT"),
                    lost = false,
                )
            }
        } catch (_: IOException) {
            if (++failedPolls >= LOST_AFTER_POLLS) stateFlow.update { it.copy(lost = true, playing = false, buffering = false) }
        }
    }

    private fun transportState(): String {
        val reply = soap(device.avTransportUrl, AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
        return tag(reply, "CurrentTransportState") ?: ""
    }

    private fun soap(controlUrl: String, service: String, action: String, args: List<Pair<String, String>>): String {
        val body = envelope(service, action, args).toByteArray(Charsets.UTF_8)
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IOException("$action answered $code")
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val TIMEOUT_MS = 4_000
        private const val LOST_AFTER_POLLS = 4
        private const val SEEK_WORTH_MS = 1_500L
        private const val START_SEEK_TRIES = 8

        private fun now() = System.nanoTime() / 1_000_000

        fun envelope(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
            args.forEach { (name, value) -> append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>') }
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }

        /** The song as a DIDL-Lite item, the way UPnP describes media to a renderer. */
        fun didl(media: DlnaMedia): String = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(escape(media.title)).append("</dc:title>")
            append("<upnp:artist>").append(escape(media.artist)).append("</upnp:artist>")
            append("<dc:creator>").append(escape(media.artist)).append("</dc:creator>")
            media.album?.let { append("<upnp:album>").append(escape(it)).append("</upnp:album>") }
            media.artworkUrl?.let { append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"http-get:*:").append(escape(media.mimeType)).append(":*\"")
            if (media.durationMs > 0) append(" duration=\"").append(clock(media.durationMs)).append(".000\"")
            append('>').append(escape(media.url)).append("</res>")
            append("</item></DIDL-Lite>")
        }

        fun escape(text: String): String = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }

        private val tagPatterns = ConcurrentHashMap<String, Regex>()

        /** The first `<name>` in a SOAP answer, whatever namespace prefix the renderer gave it. */
        fun tag(xml: String, name: String): String? =
            tagPatterns.getOrPut(name) { Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?$name>") }
                .find(xml)?.groupValues?.get(1)?.trim()?.let(DlnaDiscovery::unescape)

        /** h:mm:ss, the way UPnP writes a time. */
        fun clock(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        }

        fun parseClock(text: String): Long? {
            if (text.isBlank() || text == "NOT_IMPLEMENTED") return null
            val parts = text.split(':')
            if (parts.size != 3) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return ((hours * 3600 + minutes * 60) * 1000) + (seconds * 1000).toLong()
        }
    }
}
