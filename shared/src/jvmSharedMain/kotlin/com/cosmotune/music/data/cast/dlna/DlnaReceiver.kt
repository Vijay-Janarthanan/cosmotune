package com.cosmotune.music.data.cast.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A video a sender asked this device to play: [url] is what it plays, the rest comes from the sender's DIDL-Lite. */
data class ReceivedMedia(val url: String, val title: String, val mimeType: String?, val artworkUrl: String?, val durationMs: Long)

/** What the receiving player is doing, in UPnP's words ([transport] is PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT). */
data class ReceiverStatus(val transport: String = "NO_MEDIA_PRESENT", val positionMs: Long = 0, val durationMs: Long = 0)

/** The player a [DlnaReceiver] drives. Calls arrive on the receiver's own threads; the host moves them where they belong. */
interface DlnaReceiverHost {
    /** Plays [media] from its start (a sender seeks afterwards to resume). */
    fun play(media: ReceivedMedia)
    fun resume()
    fun pause()
    fun seek(positionMs: Long)
    fun stop()
    fun status(): ReceiverStatus
    /** The volume, 0 to 100, or null when this device does not say. */
    fun volume(): Int? = null
    fun setVolume(level: Int) {}
}

/**
 * This device as a UPnP media renderer on the local network, so a phone's or computer's "Play on a TV" (Cosmotune's own
 * [DlnaRenderer], or any DLNA sender) finds it by SSDP and plays a video here: SetAVTransportURI then Play opens it, and
 * Pause, Seek, Stop, the position and the volume follow the sender's controls.
 *
 * It only exists while the listener has turned it on. It takes http and https addresses only, a SOAP body of at most 64 KB,
 * and four requests at a time. [ssdpPort] null leaves SSDP out (a test drives the HTTP side alone).
 */
class DlnaReceiver(
    private val host: DlnaReceiverHost,
    val name: String,
    private val address: InetAddress,
    port: Int = 0,
    private val udn: String = "uuid:" + UUID.nameUUIDFromBytes(name.toByteArray()).toString(),
    private val ssdpGroup: String = "239.255.255.250",
    private val ssdpPort: Int? = 1900,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listener = ServerSocket(port, 16, address)
    val location: String = "http://${hostText(address)}:${listener.localPort}$DESCRIPTION"
    @Volatile private var current: ReceivedMedia? = null
    @Volatile private var pending: ReceivedMedia? = null
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { action -> Thread(action, "cosmotune-receiver-request").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    private val acceptor = thread(name = "cosmotune-receiver-accept", isDaemon = true) {
        while (!closed.get()) try {
            val socket = listener.accept()
            socket.soTimeout = 4_000
            try { workers.execute { socket.use { serve(it) } } } catch (_: Exception) { socket.close() }
        } catch (_: Exception) { if (closed.get()) break }
    }

    private val ssdp: MulticastSocket? = ssdpPort?.let { p ->
        runCatching {
            MulticastSocket(p).apply {
                reuseAddress = true
                soTimeout = 1_000
                val group = InetSocketAddress(InetAddress.getByName(ssdpGroup), p)
                val face = NetworkInterface.getByInetAddress(address)
                if (face != null) joinGroup(group, face) else @Suppress("DEPRECATION") joinGroup(group.address)
            }
        }.getOrNull()
    }

    private val announcer = ssdp?.let { socket ->
        thread(name = "cosmotune-receiver-ssdp", isDaemon = true) {
            val buffer = ByteArray(4_096)
            while (!closed.get()) try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val reply = ssdpReply(String(packet.data, 0, packet.length, Charsets.UTF_8), location, udn) ?: continue
                val bytes = reply.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) { if (closed.get()) break }
        }
    }

    private fun serve(socket: Socket) {
        val request = runCatching { read(socket.getInputStream()) }.getOrNull() ?: return respond(socket, 400, "text/plain", "Bad request")
        when {
            request.method == "GET" && request.path == DESCRIPTION -> respond(socket, 200, XML, description(name, udn))
            request.method == "GET" && request.path == TRANSPORT_SCPD -> respond(socket, 200, XML, scpd(TRANSPORT_ACTIONS))
            request.method == "GET" && request.path == RENDERING_SCPD -> respond(socket, 200, XML, scpd(RENDERING_ACTIONS))
            request.method == "POST" && (request.path == TRANSPORT_CONTROL || request.path == RENDERING_CONTROL) -> {
                val action = soapAction(request.headers["soapaction"].orEmpty(), request.body)
                val answer = runCatching { act(action, request.body) }.getOrNull()
                if (answer == null) respond(socket, 500, XML, fault(401, "Invalid Action"))
                else respond(socket, 200, XML, DlnaRenderer.envelope(
                    if (request.path == TRANSPORT_CONTROL) DlnaRenderer.AV_TRANSPORT else DlnaRenderer.RENDERING_CONTROL, "${action}Response", answer))
            }
            else -> respond(socket, 404, "text/plain", "Not found")
        }
    }

    /** The output arguments of [action], or null when it is not one this receiver knows or its arguments are wrong. */
    private fun act(action: String, body: String): List<Pair<String, String>>? {
        fun arg(name: String) = DlnaRenderer.tag(body, name)
        return when (action) {
            "SetAVTransportURI" -> {
                val url = arg("CurrentURI")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
                pending = media(url, arg("CurrentURIMetaData").orEmpty())
                emptyList()
            }
            "Play" -> {
                val next = pending
                if (next != null) { pending = null; current = next; host.play(next) }
                else if (current != null) host.resume() else return null
                emptyList()
            }
            "Pause" -> { host.pause(); emptyList() }
            "Stop" -> { pending = null; if (current != null) host.stop(); current = null; emptyList() }
            "Seek" -> {
                val target = arg("Target")?.let(DlnaRenderer::parseClock) ?: return null
                host.seek(target); emptyList()
            }
            "GetTransportInfo" -> {
                val status = if (current == null) ReceiverStatus() else host.status()
                listOf("CurrentTransportState" to status.transport, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            }
            "GetPositionInfo" -> {
                val status = if (current == null) ReceiverStatus() else host.status()
                val length = status.durationMs.takeIf { it > 0 } ?: current?.durationMs ?: 0
                listOf("Track" to if (current == null) "0" else "1", "TrackDuration" to DlnaRenderer.clock(length), "TrackMetaData" to "",
                    "TrackURI" to current?.url.orEmpty(), "RelTime" to DlnaRenderer.clock(status.positionMs), "AbsTime" to DlnaRenderer.clock(status.positionMs),
                    "RelCount" to "2147483647", "AbsCount" to "2147483647")
            }
            "GetMediaInfo" -> listOf("NrTracks" to if (current == null) "0" else "1", "MediaDuration" to DlnaRenderer.clock(current?.durationMs ?: 0),
                "CurrentURI" to current?.url.orEmpty(), "CurrentURIMetaData" to "", "NextURI" to "", "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to "Play,Pause,Stop,Seek")
            "GetVolume" -> listOf("CurrentVolume" to (host.volume() ?: 100).toString())
            "SetVolume" -> { host.setVolume(arg("DesiredVolume")?.toIntOrNull()?.coerceIn(0, 100) ?: return null); emptyList() }
            "GetMute" -> listOf("CurrentMute" to "0")
            else -> null
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun read(input: InputStream): Request? {
        fun line(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
                if (b == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                out.write(b)
                if (out.size() > 4_096) return null
            }
        }
        val first = line()?.split(' ') ?: return null
        if (first.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = line() ?: return null
            if (h.isEmpty()) break
            if (headers.size > 40) return null
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':', "").trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var at = 0
        while (at < length) { val n = input.read(body, at, length - at); if (n < 0) return null; at += n }
        return Request(first[0], first[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
    }

    private fun respond(socket: Socket, status: Int, type: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Internal Server Error" }
        runCatching {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                    "Server: $SERVER\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { ssdp?.close() }
        workers.shutdownNow()
        acceptor.interrupt()
        announcer?.interrupt()
    }

    companion object {
        private const val DESCRIPTION = "/dlna/description.xml"
        private const val TRANSPORT_CONTROL = "/dlna/AVTransport/control"
        private const val RENDERING_CONTROL = "/dlna/RenderingControl/control"
        private const val TRANSPORT_SCPD = "/dlna/AVTransport/scpd.xml"
        private const val RENDERING_SCPD = "/dlna/RenderingControl/scpd.xml"
        private const val XML = "text/xml; charset=\"utf-8\""
        private const val SERVER = "Cosmotune/1 UPnP/1.0 DLNADOC/1.50"
        private const val MAX_BODY = 64 * 1024
        private val TRANSPORT_ACTIONS = listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo", "GetPositionInfo",
            "GetMediaInfo", "GetTransportSettings", "GetCurrentTransportActions")
        private val RENDERING_ACTIONS = listOf("GetVolume", "SetVolume", "GetMute")
        private val SEARCH_TARGETS = setOf("ssdp:all", "upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1",
            DlnaRenderer.AV_TRANSPORT, DlnaRenderer.RENDERING_CONTROL)

        private fun hostText(address: InetAddress) = address.hostAddress.let { if (':' in it) "[${it.substringBefore('%')}]" else it }

        /** The unicast answer to an SSDP `M-SEARCH` that asks for a renderer like this one, or null for anything else. */
        fun ssdpReply(request: String, location: String, udn: String): String? {
            if (!request.startsWith("M-SEARCH", ignoreCase = true)) return null
            if (DlnaDiscovery.header(request, "MAN")?.trim('"')?.equals("ssdp:discover", true) != true) return null
            val st = DlnaDiscovery.header(request, "ST") ?: return null
            if (st !in SEARCH_TARGETS && st != udn) return null
            val usn = if (st == udn) udn else "$udn::$st"
            return "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\nSERVER: $SERVER\r\nST: $st\r\nUSN: $usn\r\n\r\n"
        }

        /** The action a SOAP request names, from its SOAPACTION header (`"urn:…:AVTransport:1#Play"`) or else its body. */
        fun soapAction(header: String, body: String): String =
            header.trim('"', ' ').substringAfter('#', "").ifEmpty {
                Regex("<s(?:oap)?:Body[^>]*>\\s*<(?:\\w+:)?(\\w+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1).orEmpty()
            }

        /** What a sender's SetAVTransportURI says it is sending; a title from the DIDL-Lite, else the file's name. */
        fun media(url: String, didl: String): ReceivedMedia {
            val meta = DlnaDiscovery.unescape(didl)
            fun first(tag: String) = Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?>\\s*([^<]*?)\\s*</(?:\\w+:)?$tag>").find(meta)?.groupValues?.get(1)
                ?.let(DlnaDiscovery::unescape)?.takeIf { it.isNotBlank() }
            val protocol = Regex("protocolInfo=\"[^:]*:[^:]*:([^:\"]+)").find(meta)?.groupValues?.get(1)?.takeIf { '/' in it }
            val duration = Regex("duration=\"([^\"]+)\"").find(meta)?.groupValues?.get(1)?.let(DlnaRenderer::parseClock) ?: 0
            val name = url.substringBefore('?').substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            return ReceivedMedia(url, (first("title") ?: name.ifBlank { "Video" }).take(200), protocol, first("albumArtURI"), duration)
        }

        fun description(name: String, udn: String): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device>")
            append("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>")
            append("<friendlyName>").append(DlnaRenderer.escape(name)).append("</friendlyName>")
            append("<manufacturer>Cosmotune</manufacturer><modelName>Cosmotune</modelName>")
            append("<UDN>").append(udn).append("</UDN><serviceList>")
            append("<service><serviceType>").append(DlnaRenderer.AV_TRANSPORT).append("</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>")
            append("<SCPDURL>$TRANSPORT_SCPD</SCPDURL><controlURL>$TRANSPORT_CONTROL</controlURL><eventSubURL>/dlna/AVTransport/event</eventSubURL></service>")
            append("<service><serviceType>").append(DlnaRenderer.RENDERING_CONTROL).append("</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>")
            append("<SCPDURL>$RENDERING_SCPD</SCPDURL><controlURL>$RENDERING_CONTROL</controlURL><eventSubURL>/dlna/RenderingControl/event</eventSubURL></service>")
            append("</serviceList></device></root>")
        }

        private fun scpd(actions: List<String>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
            actions.forEach { append("<action><name>").append(it).append("</name></action>") }
            append("</actionList><serviceStateTable/></scpd>")
        }

        private fun fault(code: Int, text: String) =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
                "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                "<errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    }
}
