package com.cosmotune.video.cast.dial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DIAL texts a phone's YouTube app reads, and a launch through the real HTTP server. */
class DialTest {
    @Test fun onlyADialOrAllSearchIsAnswered() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"
        assertEquals(Dial.SERVICE, Dial.searchTarget(search))
        assertEquals(Dial.SERVICE, Dial.searchTarget(search.replace(Dial.SERVICE, "ssdp:all")))
        assertNull(Dial.searchTarget(search.replace(Dial.SERVICE, "urn:schemas-upnp-org:device:MediaRenderer:1")))
        assertNull(Dial.searchTarget("NOTIFY * HTTP/1.1\r\nST: ${Dial.SERVICE}\r\n\r\n"))
    }

    @Test fun theReplyPointsAtTheDescription() {
        val reply = Dial.searchReply(Dial.SERVICE, "http://192.168.1.5:8008/dd.xml", "abc")
        assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n") && reply.endsWith("\r\n\r\n"))
        assertTrue("LOCATION: http://192.168.1.5:8008/dd.xml\r\n" in reply)
        assertTrue("USN: uuid:abc::${Dial.SERVICE}" in reply)
    }

    @Test fun theAppStateCarriesTheLoungeIds() {
        val stopped = Dial.appXml(false, null)
        assertTrue("<state>stopped</state>" in stopped && "additionalData" !in stopped)
        val running = Dial.appXml(true, DialScreen("scr<1>", "dev", "tok"))
        assertTrue("<state>running</state>" in running && "<screenId>scr&lt;1&gt;</screenId>" in running && "<loungeToken>tok</loungeToken>" in running)
    }

    @Test fun aLaunchHandsThePairingCodeOver() = runBlocking {
        val code = CompletableDeferred<String>()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = DialServer("Cosmotune", screen = { DialScreen("s", "d", "t") }, onPair = { code.complete(it) }, httpPort = port, ssdp = false)
        val job = launch(Dispatchers.IO) { server.run() }
        val crlf = "\r\n"
        try {
            val app = withTimeout(5_000) { ask(port, "GET /apps/YouTube HTTP/1.1${crlf}Host: x$crlf$crlf") }
            assertTrue(app.startsWith("HTTP/1.1 200 OK") && "<state>stopped</state>" in app, app)
            val body = "pairingCode=abc-123&theme=cl"
            val launched = ask(port, "POST /apps/YouTube HTTP/1.1${crlf}Host: x${crlf}Content-Length: ${body.length}$crlf$crlf$body")
            assertTrue(launched.startsWith("HTTP/1.1 201 Created") && "/apps/YouTube/run" in launched, launched)
            assertEquals("abc-123", withTimeout(5_000) { code.await() })
            assertTrue("<state>running</state>" in ask(port, "GET /apps/YouTube HTTP/1.1${crlf}Host: x$crlf$crlf"))
            assertTrue(ask(port, "GET /dd.xml HTTP/1.1${crlf}Host: x$crlf$crlf").contains("Application-URL: http://127.0.0.1:$port/apps/"))
        } finally {
            job.cancel()
        }
    }

    private suspend fun ask(port: Int, request: String): String {
        repeat(50) {
            val answer = runCatching {
                java.net.Socket("127.0.0.1", port).use { s ->
                    s.getOutputStream().write(request.toByteArray())
                    s.getInputStream().readBytes().decodeToString()
                }
            }.getOrNull()
            if (answer != null) return answer
            kotlinx.coroutines.delay(100)
        }
        error("no answer on $port")
    }
}
