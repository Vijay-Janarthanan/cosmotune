// The SSDP search and the reading of a renderer's description follow Mochy Music's foss/cast/CastDiscovery.kt
// (https://github.com/realidkroo/Mochy-Music, GPL-3.0, revision 8dd8f3b0), adapted for Cosmotune under the same licence:
// plain JVM sockets and a regular-expression reader instead of Android's NSD and XmlPullParser, so the phone and the desktop
// share it. See NOTICE.md.

package com.cosmotune.music.data.cast.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A DLNA / UPnP media renderer on the local network: a smart TV, a network speaker, a receiver. */
data class DlnaDevice(
    val id: String,
    val name: String,
    val model: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

/**
 * Finds media renderers with SSDP while [start]ed: an `M-SEARCH` for `MediaRenderer:1` sent a few times (a TV can miss
 * one), every answer's `LOCATION` read once for its name and the control addresses of its AVTransport and
 * RenderingControl services. What was found stays listed between searches.
 *
 * [group] and [port] are the SSDP multicast address; tests point them at a local fake.
 */
class DlnaDiscovery(
    private val group: String = "239.255.255.250",
    private val port: Int = 1900,
    private val listenMs: Long = 3_000,
    private val gapMs: Long = 7_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val found = ConcurrentHashMap<String, DlnaDevice>()
    private val describing = ConcurrentHashMap.newKeySet<String>()
    private val devicesFlow = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = devicesFlow.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive != true) job = scope.launch { search() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun search() {
        val request = searchRequest(group, port).toByteArray(Charsets.US_ASCII)
        val socket = runCatching { DatagramSocket().apply { soTimeout = 500 } }.getOrNull() ?: return
        try {
            val target = InetAddress.getByName(group)
            val buffer = ByteArray(8_192)
            while (currentCoroutineContext().isActive) {
                repeat(2) { runCatching { socket.send(DatagramPacket(request, request.size, target, port)) } }
                val until = System.currentTimeMillis() + listenMs
                while (System.currentTimeMillis() < until && currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val location = header(String(packet.data, 0, packet.length, Charsets.UTF_8), "LOCATION") ?: continue
                    if (describing.add(location)) scope.launch { describe(location) }
                }
                delay(gapMs)
            }
        } finally {
            socket.close()
        }
    }

    private fun describe(location: String) {
        try {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            val xml = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            connection.disconnect()
            val device = parseDescription(xml, location) ?: return
            found[device.id] = device
            devicesFlow.value = found.values.sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            // Asked again on the next search: a TV waking up can take a moment to answer.
            describing.remove(location)
        }
    }

    companion object {
        fun searchRequest(group: String = "239.255.255.250", port: Int = 1900) =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $group:$port\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        fun header(reply: String, name: String): String? =
            reply.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
                ?.takeIf { it.isNotEmpty() }

        /**
         * A renderer from its device description: the first friendly name, model and UDN, and the control URLs of its
         * AVTransport and RenderingControl services resolved against `URLBase` or the description's own address. Null
         * when it has no AVTransport, which a renderer cannot do without.
         */
        fun parseDescription(xml: String, location: String): DlnaDevice? {
            fun first(tag: String) = Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?>\\s*([^<]*?)\\s*</(?:\\w+:)?$tag>").find(xml)
                ?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
            val services = Regex("<(?:\\w+:)?service(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.groupValues[1] }.toList()
            fun control(kind: String): String? = services.firstOrNull { it.contains(":$kind:") }?.let { block ->
                Regex("<(?:\\w+:)?controlURL>\\s*([^<]*?)\\s*</(?:\\w+:)?controlURL>").find(block)?.groupValues?.get(1)?.let(::unescape)
            }
            val transport = control("AVTransport") ?: return null
            val root = runCatching { URL(first("URLBase") ?: location) }.getOrNull() ?: return null
            val model = first("modelName")
            return DlnaDevice(
                id = "dlna:" + (first("UDN") ?: location),
                name = first("friendlyName") ?: model ?: "TV",
                model = model ?: "DLNA renderer",
                avTransportUrl = URL(root, transport).toString(),
                renderingControlUrl = control("RenderingControl")?.let { URL(root, it).toString() },
            )
        }

        internal fun unescape(text: String) =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
