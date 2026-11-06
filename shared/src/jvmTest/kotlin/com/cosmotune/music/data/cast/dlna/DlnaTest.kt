package com.cosmotune.music.data.cast.dlna

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTest {

    private val closers = ArrayList<() -> Unit>()

    @AfterTest
    fun closeAll() = closers.forEach { runCatching(it) }

    /** A renderer: its description, and SOAP control that remembers every action and answers like a TV. */
    private inner class FakeRenderer {
        val actions: MutableList<String> = Collections.synchronizedList(ArrayList())
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var transport = "STOPPED"
        @Volatile var volume = 30
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val base get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/description.xml") { exchange ->
                val xml = """<?xml version="1.0"?>
                    <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                      <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                      <friendlyName>Living Room TV</friendlyName><modelName>Bravia &amp; Co</modelName>
                      <UDN>uuid:1234</UDN>
                      <serviceList>
                        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                          <controlURL>/rc</controlURL></service>
                        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                          <controlURL>/av</controlURL></service>
                      </serviceList></device></root>"""
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(xml.toByteArray()) }
            }
            val control = { path: String ->
                server.createContext(path) { exchange ->
                    val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().substringAfter('#').trim('"')
                    actions += action
                    bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    when (action) {
                        "Play" -> transport = "PLAYING"
                        "Pause" -> transport = "PAUSED_PLAYBACK"
                        "Stop" -> transport = "STOPPED"
                    }
                    val reply = when (action) {
                        "GetTransportInfo" -> "<CurrentTransportState>$transport</CurrentTransportState>"
                        "GetPositionInfo" -> "<TrackDuration>0:03:30</TrackDuration><RelTime>0:01:05</RelTime>"
                        "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
                        else -> ""
                    }
                    val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                        "<u:${action}Response xmlns:u=\"x\">$reply</u:${action}Response></s:Body></s:Envelope>"
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
            }
            control("/av")
            control("/rc")
            server.start()
            closers += { server.stop(0) }
        }
    }

    @Test
    fun `a renderer is found by its SSDP answer and described`() = runBlocking {
        val renderer = FakeRenderer()
        // A unicast stand-in for the multicast group, answering M-SEARCH the way a TV does.
        val ssdp = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closers += { ssdp.close() }
        thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            while (!ssdp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { ssdp.receive(packet) }.onFailure { return@thread }
                val asked = String(packet.data, 0, packet.length)
                if (!asked.startsWith("M-SEARCH") || "MediaRenderer" !in asked) continue
                val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: ${renderer.base}/description.xml\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                val bytes = answer.toByteArray()
                runCatching { ssdp.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
            }
        }
        val discovery = DlnaDiscovery(group = "127.0.0.1", port = ssdp.localPort, listenMs = 500, gapMs = 200)
        discovery.start()
        closers += { discovery.stop() }
        val devices = withTimeout(10_000) { discovery.devices.first { it.isNotEmpty() } }
        val device = devices.single()
        assertEquals("Living Room TV", device.name)
        assertEquals("Bravia & Co", device.model)
        assertEquals("dlna:uuid:1234", device.id)
        assertEquals("${renderer.base}/av", device.avTransportUrl)
        assertEquals("${renderer.base}/rc", device.renderingControlUrl)
    }

    @Test
    fun `the renderer is driven with SOAP and polled`() = runBlocking {
        val fake = FakeRenderer()
        val device = DlnaDevice("dlna:x", "TV", "Model", "${fake.base}/av", "${fake.base}/rc")
        val renderer = DlnaRenderer(device, pollMs = 100)
        closers += { runBlocking { renderer.close(stopPlayback = false) } }
        renderer.connect()
        assertEquals(0.3f, renderer.state.value.volume)
        renderer.load(DlnaMedia("http://192.168.1.5:5000/m/abc", "audio/mp4", "Kannalane", "A. R. Rahman & K. S. Chithra", durationMs = 210_000))
        assertTrue("SetAVTransportURI" in fake.actions && "Play" in fake.actions)
        val setUri = fake.bodies[fake.actions.indexOf("SetAVTransportURI")]
        assertTrue("<CurrentURI>http://192.168.1.5:5000/m/abc</CurrentURI>" in setUri)
        // The metadata is DIDL-Lite, escaped inside the SOAP body, and the artist's ampersand is escaped twice.
        assertTrue("&lt;dc:title&gt;Kannalane&lt;/dc:title&gt;" in setUri)
        assertTrue("A. R. Rahman &amp;amp; K. S. Chithra" in setUri)
        val playing = withTimeout(5_000) { renderer.state.first { it.playing && it.positionMs == 65_000L } }
        assertEquals(210_000L, playing.durationMs)
        renderer.pause()
        renderer.seek(90_000)
        renderer.setVolume(0.5f)
        assertTrue(fake.bodies.any { "<Target>0:01:30</Target>" in it })
        assertTrue(fake.bodies.any { "<DesiredVolume>50</DesiredVolume>" in it })
        // The TV stopping on its own after playing is the song's end.
        fake.transport = "STOPPED"
        withTimeout(5_000) { renderer.state.first { it.finished } }
        Unit
    }

    @Test
    fun `times and tags are read the way renderers write them`() {
        assertEquals(3_723_500L, DlnaRenderer.parseClock("1:02:03.500"))
        assertNull(DlnaRenderer.parseClock("NOT_IMPLEMENTED"))
        assertEquals("1:02:03", DlnaRenderer.clock(3_723_999))
        assertEquals("PLAYING", DlnaRenderer.tag("<a:CurrentTransportState>PLAYING</a:CurrentTransportState>", "CurrentTransportState"))
        assertNull(DlnaDiscovery.parseDescription("<root><device><friendlyName>No transport</friendlyName></device></root>", "http://x/"))
        assertEquals("http://h:1/x", DlnaDiscovery.header("HTTP/1.1 200 OK\r\nLocation: http://h:1/x\r\n\r\n", "LOCATION"))
    }

    @Test
    fun `ranges are read as renderers send them`() {
        assertEquals(0L to 999L, DlnaMediaRelay.parseRange(null, 1000))
        assertEquals(100L to 999L, DlnaMediaRelay.parseRange("bytes=100-", 1000))
        assertEquals(100L to 199L, DlnaMediaRelay.parseRange("bytes=100-199", 1000))
        assertEquals(900L to 999L, DlnaMediaRelay.parseRange("bytes=-100", 1000))
        assertEquals(10L to 999L, DlnaMediaRelay.parseRange("bytes=10-5000", 1000))
        assertNull(DlnaMediaRelay.parseRange("bytes=1000-", 1000))
    }

    @Test
    fun `the relay serves a file and windows a remote stream`() {
        val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
        // Upstream that, like googlevideo, refuses anything but a ranged request.
        val upstreamRequests = Collections.synchronizedList(ArrayList<String>())
        val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        upstream.createContext("/audio") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            upstreamRequests += "${exchange.requestHeaders.getFirst("X-Client")}|$range"
            val (from, to) = DlnaMediaRelay.parseRange(range, payload.size.toLong()) ?: (0L to -1L)
            if (range == null) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
            exchange.sendResponseHeaders(206, to - from + 1)
            exchange.responseBody.use { it.write(payload, from.toInt(), (to - from + 1).toInt()) }
        }
        upstream.start()
        closers += { upstream.stop(0) }
        val relay = DlnaMediaRelay(windowBytes = 64 * 1024)
        closers += { relay.close() }

        val remote = relay.publish(
            DlnaMediaRelay.Source.Remote("http://127.0.0.1:${upstream.address.port}/audio", mapOf("X-Client" to "IOS"), "audio/mp4"),
            "127.0.0.1",
        )
        val whole = fetch(remote, range = null)
        assertEquals(200, whole.first)
        assertContentEquals(payload, whole.second)
        assertTrue(upstreamRequests.all { it.startsWith("IOS|bytes=") }, "every upstream request was ranged and dressed")
        assertTrue(upstreamRequests.size > 4, "the stream was fetched in windows")
        val part = fetch(remote, range = "bytes=1000-1999")
        assertEquals(206, part.first)
        assertContentEquals(payload.copyOfRange(1000, 2000), part.second)

        val file = File.createTempFile("relay", ".flac").apply { writeBytes(payload); deleteOnExit() }
        val local = relay.publish(DlnaMediaRelay.Source.LocalFile(file, "audio/flac"), "127.0.0.1")
        assertContentEquals(payload.copyOfRange(299_000, 300_000), fetch(local, range = "bytes=-1000").second)
        assertEquals(404, fetch(local.substringBeforeLast('/') + "/nope", range = null).first)
    }

    @Test
    fun `local files are typed by extension`() {
        assertEquals("audio/flac", DlnaCast.mimeOf("a.FLAC"))
        assertEquals("audio/mpeg", DlnaCast.mimeOf("b.mp3"))
        assertFalse(DlnaCast.mimeOf("c.xyz").startsWith("audio"))
    }

    private fun fetch(url: String, range: String?): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val body = if (code < 400) connection.inputStream.use { it.readBytes() } else ByteArray(0)
        connection.disconnect()
        return code to body
    }
}
