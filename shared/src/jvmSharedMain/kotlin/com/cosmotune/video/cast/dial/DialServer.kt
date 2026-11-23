package com.cosmotune.video.cast.dial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** What the phone's app needs to find this screen's lounge: its id, this device's id and the lounge token. */
data class DialScreen(val screenId: String, val deviceId: String, val loungeToken: String?)

/**
 * This device as a DIAL screen on the home network (what the YouTube app looks for before it shows "cast to"): it answers the
 * SSDP search for `urn:dial-multiscreen-org:service:dial:1`, serves the device description and the YouTube app's state, and a
 * phone's launch (a POST with `pairingCode`) hands the code to [onPair], which registers it with the lounge so the phone joins
 * without a code typed. Runs until cancelled. [screen] is the lounge screen now (null while it is being set up).
 */
class DialServer(
    private val name: String,
    private val screen: () -> DialScreen?,
    private val onPair: suspend (String) -> Unit,
    private val uuid: String = UUID.randomUUID().toString(),
    private val httpPort: Int = 0,
    private val ssdp: Boolean = true,
) {
    @Volatile private var running = false

    suspend fun run() {
        val http = withContext(Dispatchers.IO) { ServerSocket(httpPort) }
        try {
            coroutineScope {
                launch(Dispatchers.IO) { serveHttp(http) }
                // Another program may hold the SSDP port without sharing it: then only the HTTP side runs.
                if (ssdp) launch(Dispatchers.IO) { runCatching { answerSearches(http.localPort) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
            }
        } finally {
            runCatching { http.close() }
        }
    }

    private suspend fun answerSearches(port: Int) {
        val socket = MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            soTimeout = 1_000
        }
        socket.use {
            val group = InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
            val joined = lanInterfaces().mapNotNull { nif -> runCatching { it.joinGroup(group, nif) }.getOrNull()?.let { nif } }
            if (joined.isEmpty()) runCatching { @Suppress("DEPRECATION") it.joinGroup(group.address) }
            val buffer = ByteArray(2048)
            while (coroutineContext.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    it.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    if (it.isClosed) return
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val st = Dial.searchTarget(text) ?: continue
                val local = localAddressFor(packet.address) ?: continue
                val reply = Dial.searchReply(st, "http://${local.hostAddress}:$port/dd.xml", uuid).toByteArray(Charsets.UTF_8)
                runCatching { DatagramSocket().use { out -> out.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) } }
            }
        }
    }

    private suspend fun serveHttp(server: ServerSocket) {
        server.soTimeout = 1_000
        while (coroutineContext.isActive && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return
            }
            client.use { runCatching { handle(it, server.localPort) } }
        }
    }

    private suspend fun handle(socket: Socket, port: Int) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = readRequest(input) ?: return
        val base = "http://${socket.localAddress.hostAddress}:$port"
        val out = socket.getOutputStream()
        val path = request.path.substringBefore('?')
        val answer: String = when {
            request.method == "GET" && path == "/dd.xml" ->
                Dial.response(200, "OK", Dial.deviceXml(name, uuid), "text/xml; charset=utf-8", mapOf("Application-URL" to "$base/apps/"))
            path == "/apps/YouTube" && request.method == "GET" ->
                Dial.response(200, "OK", Dial.appXml(running, screen()), "text/xml; charset=utf-8")
            path == "/apps/YouTube" && request.method == "POST" -> {
                val code = Dial.form(request.body)["pairingCode"]
                if (code.isNullOrBlank() || screen() == null) {
                    Dial.response(503, "Service Unavailable", "", "text/plain")
                } else {
                    running = true
                    onPair(code)
                    Dial.response(201, "Created", "", "text/plain", mapOf("Location" to "$base/apps/YouTube/run"))
                }
            }
            path == "/apps/YouTube/run" && request.method == "DELETE" -> {
                running = false
                Dial.response(200, "OK", "", "text/plain")
            }
            else -> Dial.response(404, "Not Found", "", "text/plain")
        }
        out.write(answer.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private class HttpRequest(val method: String, val path: String, val body: String)

    private fun readRequest(input: InputStream): HttpRequest? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.length > 16_384) return null
        }
        val lines = head.split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) return null
        val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceIn(0, 65_536) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(first[0].uppercase(), first[1], String(body, 0, read, Charsets.UTF_8))
    }

    private fun localAddressFor(remote: InetAddress): InetAddress? = runCatching {
        DatagramSocket().use { it.connect(remote, SSDP_PORT); it.localAddress.takeIf { a -> !a.isAnyLocalAddress } }
    }.getOrNull()

    private fun lanInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
            nif.isUp && !nif.isLoopback && nif.supportsMulticast() && nif.inetAddresses.toList().any { it is Inet4Address }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
    }
}

/** The DIAL texts, kept apart from the sockets so they can be checked. */
object Dial {
    const val SERVICE = "urn:dial-multiscreen-org:service:dial:1"

    /** The search target of an SSDP M-SEARCH this screen answers (DIAL, or everything), or null. */
    fun searchTarget(packet: String): String? {
        val lines = packet.split("\r\n", "\n")
        if (!lines.first().trim().startsWith("M-SEARCH", ignoreCase = true)) return null
        val st = lines.firstOrNull { it.startsWith("ST:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: return null
        return st.takeIf { it == SERVICE || it == "ssdp:all" }?.let { SERVICE }
    }

    fun searchReply(st: String, location: String, uuid: String): String = listOf(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL: max-age=1800",
        "EXT:",
        "LOCATION: $location",
        "SERVER: Linux/1.0 UPnP/1.1 Cosmotune/1.0",
        "ST: $st",
        "USN: uuid:$uuid::$st",
        "BOOTID.UPNP.ORG: 1",
        "",
        "",
    ).joinToString("\r\n")

    fun deviceXml(name: String, uuid: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
    <friendlyName>${escape(name)}</friendlyName>
    <manufacturer>Cosmotune</manufacturer>
    <modelName>Cosmotune</modelName>
    <UDN>uuid:$uuid</UDN>
    <serviceList>
      <service>
        <serviceType>$SERVICE</serviceType>
        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
        <controlURL>/dial/control</controlURL>
        <eventSubURL>/dial/event</eventSubURL>
        <SCPDURL>/dial/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    /** The YouTube app's state: running once a phone launched it, with the lounge's ids for the phone. */
    fun appXml(running: Boolean, screen: DialScreen?): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">""").append('\n')
        append("  <name>YouTube</name>\n")
        append("""  <options allowStop="true"/>""").append('\n')
        append("  <state>").append(if (running) "running" else "stopped").append("</state>\n")
        if (running) append("""  <link rel="run" href="run"/>""").append('\n')
        if (screen != null) {
            append("  <additionalData>\n")
            append("    <screenId>").append(escape(screen.screenId)).append("</screenId>\n")
            append("    <theme>cl</theme>\n")
            append("    <deviceId>").append(escape(screen.deviceId)).append("</deviceId>\n")
            screen.loungeToken?.let { append("    <loungeToken>").append(escape(it)).append("</loungeToken>\n") }
            append("    <loungeTokenRefreshIntervalMs>1500000</loungeTokenRefreshIntervalMs>\n")
            append("  </additionalData>\n")
        }
        append("</service>\n")
    }

    fun form(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k) to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
    }

    fun response(code: Int, reason: String, body: String, type: String, headers: Map<String, String> = emptyMap()): String {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val extra = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        return "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $bytes\r\n${extra}Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$body"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
