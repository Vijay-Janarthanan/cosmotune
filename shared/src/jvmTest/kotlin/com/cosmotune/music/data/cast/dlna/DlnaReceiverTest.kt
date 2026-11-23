package com.cosmotune.music.data.cast.dlna

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaReceiverTest {
    private class Host : DlnaReceiverHost {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var played: ReceivedMedia? = null
        @Volatile var status = ReceiverStatus("PLAYING", 61_000, 3_600_000)
        @Volatile var level = 40
        override fun play(media: ReceivedMedia) { played = media; calls += "play" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override fun stop() { calls += "stop" }
        override fun status() = status
        override fun volume() = level
        override fun setVolume(level: Int) { this.level = level; calls += "volume $level" }
    }

    private val host = Host()
    private val receiver = DlnaReceiver(host, "Cosmotune on Living room TV", InetAddress.getLoopbackAddress(), ssdpPort = null)

    @AfterTest fun close() = receiver.close()

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun cosmotuneSendersFindItsControlsInItsDescription() {
        val device = assertNotNull(DlnaDiscovery.parseDescription(get(receiver.location), receiver.location))
        assertEquals("Cosmotune on Living room TV", device.name)
        assertTrue(device.avTransportUrl.endsWith("/dlna/AVTransport/control"))
        assertNotNull(device.renderingControlUrl)
    }

    @Test fun aSenderPlaysPausesSeeksAndStopsHere() = runBlocking {
        val device = DlnaDiscovery.parseDescription(get(receiver.location), receiver.location)!!
        val renderer = DlnaRenderer(device, pollMs = 50)
        renderer.connect()
        assertEquals(0.4f, renderer.state.value.volume)
        renderer.load(DlnaMedia("https://cdn.example/film.mp4", "video/mp4", "The Film", "", durationMs = 3_600_000, video = true))
        val media = assertNotNull(host.played)
        assertEquals("https://cdn.example/film.mp4", media.url)
        assertEquals("The Film", media.title)
        assertEquals("video/mp4", media.mimeType)
        assertEquals(3_600_000, media.durationMs)
        renderer.pause(); renderer.play(); renderer.seek(90_000); renderer.setVolume(.7f)
        assertTrue(listOf("play", "pause", "resume", "seek 90000", "volume 70").all { it in host.calls }, host.calls.toString())
        kotlinx.coroutines.delay(200)
        assertEquals(61_000, renderer.state.value.positionMs)
        assertTrue(renderer.state.value.playing)
        renderer.close(stopPlayback = true)
        assertEquals("stop", host.calls.last())
    }

    @Test fun onlyWebAddressesArePlayed() = runBlocking {
        val device = DlnaDiscovery.parseDescription(get(receiver.location), receiver.location)!!
        val renderer = DlnaRenderer(device, pollMs = 10_000)
        renderer.load(DlnaMedia("file:///sdcard/secret.mp4", "video/mp4", "x", ""))
        assertNull(host.played)
        assertTrue(renderer.state.value.failed)
    }

    @Test fun answersOnlySearchesForARenderer() {
        val search = DlnaDiscovery.searchRequest()
        val reply = assertNotNull(DlnaReceiver.ssdpReply(search, "http://10.0.0.5:4000/dlna/description.xml", "uuid:abc"))
        assertEquals("http://10.0.0.5:4000/dlna/description.xml", DlnaDiscovery.header(reply, "LOCATION"))
        assertEquals("uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1", DlnaDiscovery.header(reply, "USN"))
        assertNull(DlnaReceiver.ssdpReply(search.replace("MediaRenderer", "MediaServer"), "http://x", "uuid:abc"))
        assertNull(DlnaReceiver.ssdpReply("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n", "http://x", "uuid:abc"))
    }

    @Test fun aTitleComesFromTheFileNameWhenTheSenderGivesNone() {
        val media = DlnaReceiver.media("http://nas.local/films/Big%20Buck%20Bunny.mkv?token=1", "")
        assertEquals("Big Buck Bunny.mkv", media.title)
        assertNull(media.mimeType)
        assertEquals("Play", DlnaReceiver.soapAction("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", ""))
        assertEquals("Pause", DlnaReceiver.soapAction("", "<s:Envelope><s:Body><u:Pause xmlns:u=\"x\"/></s:Body></s:Envelope>"))
    }
}
