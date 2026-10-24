package com.cosmotune.remote

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LanRemoteServerTest {
    private class Host : LanRemoteHost {
        val commands = CopyOnWriteArrayList<RemoteCommand>()
        var query: String? = null
        override fun playback() = RemotePlayback("தமிழ் Song", "Singer", true, 1234, 240_000, 0.75f)
        override suspend fun command(command: RemoteCommand) { commands += command }
        override suspend fun search(query: String): List<RemoteTrack> {
            this.query = query
            return listOf(RemoteTrack("source:recording", "<img src=x onerror=alert(1)>", "Singer"))
        }
    }
    private data class Reply(val status: Int, val head: String, val body: String)
    private fun exchange(server: LanRemoteServer, path: String, body: String? = null, key: String? = null,
        origin: String? = null, host: String? = null, extra: String = ""): Reply {
        val uri = URI(server.session.origin)
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val header = buildString {
            append(if (body == null) "GET" else "POST"); append(" $path HTTP/1.1\r\nHost: ${host ?: uri.rawAuthority}\r\n")
            key?.let { append("Authorization: Bearer $it\r\n") }
            origin?.let { append("Origin: $it\r\n") }
            if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
            append(extra); append("\r\n")
        }
        return raw(server, header.toByteArray(Charsets.US_ASCII) + bytes)
    }
    private fun raw(server: LanRemoteServer, bytes: ByteArray): Reply {
        val uri = URI(server.session.origin)
        val response = Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply { write(bytes); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        return Reply(response.substringAfter(' ').substringBefore(' ').toInt(), response.substringBefore("\r\n\r\n"), response.substringAfter("\r\n\r\n"))
    }
    private fun key(server: LanRemoteServer) = server.session.pairingUrl.substringAfter("#key=")

    @Test fun pairedBrowserGetsRealStatusAndExplicitPlaybackCommands() {
        val host = Host()
        LanRemoteServer.start(host).use { server ->
            assertEquals(200, exchange(server, "/").status)
            assertEquals(401, exchange(server, "/api/status").status)
            val status = exchange(server, "/api/status", key = key(server))
            assertEquals(200, status.status); assertTrue(status.body.contains("தமிழ் Song"))
            assertTrue(status.head.contains("Cache-Control: no-store")); assertTrue(status.head.contains("frame-ancestors 'none'"))
            assertEquals(200, exchange(server, "/api/action", """{"action":"PAUSE"}""", key(server), server.session.origin).status)
            assertEquals(200, exchange(server, "/api/action", """{"action":"PLAY"}""", key(server)).status)
            assertEquals(listOf(RemoteAction.PAUSE, RemoteAction.PLAY), host.commands.map { it.action })
            assertFalse(server.session.toString().contains(key(server)))
            val page = exchange(server, "/").body
            assertTrue(page.contains("textContent=track.title")); assertTrue(page.contains("location.hash"))
            assertFalse(page.contains("SAPISID")); assertFalse(page.contains("https://")); assertFalse(page.contains(key(server)))
        }
    }

    @Test fun foreignOriginsHostsQueryKeysAndGetActionsCannotControlPlayback() {
        val host = Host()
        LanRemoteServer.start(host).use { server ->
            assertEquals(403, exchange(server, "/api/action", """{"action":"NEXT"}""", key(server), "https://other.example").status)
            assertEquals(403, exchange(server, "/api/status", key = key(server), host = "attacker.example").status)
            assertEquals(401, exchange(server, "/api/status?key=${key(server)}").status)
            assertEquals(405, exchange(server, "/api/action", key = key(server)).status)
            assertEquals(401, exchange(server, "/api/action", """{"action":"NEXT"}""", "wrong").status)
            assertTrue(host.commands.isEmpty())
        }
    }

    @Test fun malformedAndOversizedWireRequestsAreBoundedBeforeDispatch() {
        val host = Host()
        LanRemoteServer.start(host).use { server ->
            val authority = URI(server.session.origin).rawAuthority
            assertEquals(400, raw(server, "GET /api/status HTTP/1.1\r\nHost: $authority\r\nHost: $authority\r\n\r\n".toByteArray()).status)
            assertEquals(400, raw(server, "POST /api/action HTTP/1.1\r\nHost: $authority\r\nContent-Length: 8193\r\n\r\n".toByteArray()).status)
            assertEquals(400, raw(server, "POST /api/action HTTP/1.1\r\nHost: $authority\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()).status)
            assertEquals(400, exchange(server, "/api/action", """{"action":"SEEK","positionMs":-1}""", key(server)).status)
            assertEquals(400, exchange(server, "/api/action", """{"action":"VOLUME","volume":5}""", key(server)).status)
            assertEquals(400, exchange(server, "/api/action", """{"action":"NEXT","trackId":"injected"}""", key(server)).status)
            assertTrue(host.commands.isEmpty())
        }
        assertFailsWith<IllegalArgumentException> { LanRemoteServer.start(host, InetAddress.getByName("0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { LanRemoteServer.start(host, InetAddress.getByName("8.8.8.8")) }
    }

    @Test fun onlyTracksReturnedByThisHostSessionMayBePlayed() {
        val host = Host()
        LanRemoteServer.start(host).use { server ->
            val command = """{"action":"PLAY_TRACK","trackId":"source:recording"}"""
            assertEquals(400, exchange(server, "/api/action", command, key(server)).status)
            val found = exchange(server, "/api/search?q=%E0%AE%A4%E0%AE%AE%E0%AE%BF%E0%AE%B4%E0%AF%8D", key = key(server))
            assertEquals(200, found.status); assertEquals("தமிழ்", host.query)
            assertFalse(found.body.contains("source:recording"))
            val token = Json.parseToJsonElement(found.body).jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            assertEquals(200, exchange(server, "/api/action", command.replace("source:recording", token), key(server)).status)
            assertEquals("source:recording", host.commands.single().trackId)
            assertEquals(400, exchange(server, "/api/action", command.replace("source:recording", "https://foreign/stream?secret"), key(server)).status)
        }
    }

    @Test fun stoppingClosesPendingClientsAndRestartInvalidatesTheOldKey() {
        val host = Host()
        val server = LanRemoteServer.start(host)
        val old = key(server)
        val uri = URI(server.session.origin)
        val pending = Socket(uri.host, uri.port).apply { soTimeout = 3000 }
        pending.getOutputStream().apply { write("GET / HTTP/1.1\r\n".toByteArray()); flush() }
        Thread.sleep(30)
        server.close()
        pending.use { assertTrue(runCatching { it.getInputStream().read() == -1 }.getOrDefault(true)) }
        assertFails { Socket(uri.host, uri.port).use {} }
        LanRemoteServer.start(host).use { replacement ->
            assertNotEquals(old, key(replacement))
            assertEquals(401, exchange(replacement, "/api/status", key = old).status)
        }
    }

    @Test fun stopCancelsAnInFlightCommandAndHostErrorsNeverLeakAccountDetails() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val cancelled = CompletableDeferred<Unit>()
        val host = object : LanRemoteHost {
            override fun playback(): RemotePlayback = error("private-cookie=do-not-disclose")
            override suspend fun command(command: RemoteCommand) {
                started.countDown()
                try { delay(60_000); fail("A stopped session must not execute its delayed command") }
                finally { cancelled.complete(Unit) }
            }
        }
        val server = LanRemoteServer.start(host)
        try {
            val failed = exchange(server, "/api/status", key = key(server))
            assertEquals(503, failed.status); assertFalse(failed.body.contains("private-cookie"))
            val request = async(Dispatchers.IO) { runCatching { exchange(server, "/api/action", """{"action":"NEXT"}""", key(server)) } }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            server.close()
            withTimeout(3000) { cancelled.await(); request.await() }
        } finally { server.close() }
    }

    private class QueueHost : LanRemoteHost {
        val commands = CopyOnWriteArrayList<RemoteCommand>()
        override fun playback() = RemotePlayback("Song", "Singer", true, 0, 1000, 0.5f, shuffle = true, repeat = RemoteRepeat.ALL, liked = false)
        override suspend fun command(command: RemoteCommand) { commands += command }
        override suspend fun queue() = RemoteQueue(4, (3..200).map { RemoteQueueItem(it, "Track $it" + "x".repeat(if (it == 5) 900 else 0), "Artist") })
    }

    @Test fun shuffleRepeatLoveAndUpNextAreTypedAndBounded() {
        val host = QueueHost()
        LanRemoteServer.start(host).use { server ->
            val status = Json.parseToJsonElement(exchange(server, "/api/status", key = key(server)).body).jsonObject
            assertEquals("true", status["shuffle"].toString()); assertEquals("\"ALL\"", status["repeat"].toString())
            assertEquals("false", status["liked"].toString())
            assertEquals(401, exchange(server, "/api/queue").status)
            assertEquals(405, exchange(server, "/api/queue", "{}", key(server)).status)
            val queue = Json.decodeFromString(RemoteQueue.serializer(), exchange(server, "/api/queue", key = key(server)).body)
            assertEquals(4, queue.current); assertEquals(100, queue.items.size); assertEquals(3, queue.items.first().index)
            assertEquals(500, queue.items.first { it.index == 5 }.title.length)
            for (action in listOf("SHUFFLE", "REPEAT", "LIKE")) {
                assertEquals(200, exchange(server, "/api/action", """{"action":"$action"}""", key(server)).status)
            }
            assertEquals(200, exchange(server, "/api/action", """{"action":"PLAY_QUEUE_INDEX","index":7}""", key(server)).status)
            // An index only goes with a queue jump, a jump needs one in range, and nothing else rides along.
            for (bad in listOf("""{"action":"PLAY_QUEUE_INDEX"}""", """{"action":"PLAY_QUEUE_INDEX","index":-1}""",
                    """{"action":"PLAY_QUEUE_INDEX","index":7,"positionMs":3}""", """{"action":"NEXT","index":2}""",
                    """{"action":"LIKE","trackId":"x"}""", """{"action":"REPEAT","volume":0.5}""")) {
                assertEquals(400, exchange(server, "/api/action", bad, key(server)).status, bad)
            }
            assertEquals(listOf(RemoteAction.SHUFFLE, RemoteAction.REPEAT, RemoteAction.LIKE, RemoteAction.PLAY_QUEUE_INDEX),
                host.commands.map { it.action })
            assertEquals(7, host.commands.last().index)
            val page = exchange(server, "/").body
            assertTrue(page.contains("/api/queue")); assertTrue(page.contains("PLAY_QUEUE_INDEX"))
        }
    }

    @Test fun hostsWithoutTheNewControlsKeepTheOldContract() {
        LanRemoteServer.start(Host()).use { server ->
            val status = Json.parseToJsonElement(exchange(server, "/api/status", key = key(server)).body).jsonObject
            assertEquals("null", status["shuffle"].toString()); assertEquals("null", status["liked"].toString())
            assertEquals(RemoteQueue(), Json.decodeFromString(RemoteQueue.serializer(), exchange(server, "/api/queue", key = key(server)).body))
        }
        assertEquals(listOf(RemoteRepeat.ALL, RemoteRepeat.ONE, RemoteRepeat.OFF), RemoteRepeat.entries.map { it.next() })
    }

    @Test fun thePageScriptHasNoStringBrokenAcrossLines() {
        // A raw line break inside a quoted JavaScript string stops the whole page script.
        val script = LanRemotePage.html.substringAfter("<script>").substringBefore("</script>")
        script.lines().forEachIndexed { i, line ->
            val quotes = Regex("""(?<!\\)'""").findAll(line).count()
            assertEquals(0, quotes % 2, "unbalanced quote on script line ${i + 1}: ${line.take(60)}")
        }
    }
}
