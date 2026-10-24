package com.cosmotune.music.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopAudioFileTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { Thread(it, "download-fixture").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { request -> try { handler(request) } finally { request.close() } }; start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/audio?private-token"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun checkpoint(target: File, url: String) {
        File(target.parentFile, target.name + ".part").writeText("abc")
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        File(target.parentFile, target.name + ".checkpoint").writeText("""{"urlHash":"$hash","validator":"\"same\""}""")
    }
    private fun HttpExchange.reply(code: Int, body: String, etag: String = "\"same\"", range: String? = null) {
        responseHeaders.add("ETag", etag); range?.let { responseHeaders.add("Content-Range", it) }
        sendResponseHeaders(code, body.length.toLong()); responseBody.use { it.write(body.toByteArray()) }
    }

    @Test fun resumeOnlyAppendsTheSameRenditionAtItsExactOffset() = runBlocking<Unit> {
        Server { request ->
            assertEquals("bytes=3-", request.requestHeaders.getFirst("Range"))
            assertEquals("\"same\"", request.requestHeaders.getFirst("If-Range"))
            assertEquals("identity", request.requestHeaders.getFirst("Accept-Encoding"))
            request.reply(206, "def", range = "bytes 3-5/6")
        }.use { server ->
            val target = File(temporary.newFolder(), "song.flac")
            checkpoint(target, server.url)
            DesktopAudioFileTransfer.save(server.url, emptyMap(), target, "fixture")
            assertEquals("abcdef", target.readText())
            assertFalse(File(target.parentFile, target.name + ".checkpoint").exists())
        }
    }

    @Test fun changedValidatorOrWrongOffsetCannotPublishOrDamageAnExistingFile() = runBlocking<Unit> {
        for ((etag, range) in listOf("\"changed\"" to "bytes 3-5/6", "\"same\"" to "bytes 2-4/5")) {
            Server { it.reply(206, "def", etag, range) }.use { server ->
                val target = File(temporary.newFolder(), "song.flac").apply { writeText("completed original") }
                checkpoint(target, server.url)
                assertFailsWith<IllegalArgumentException> { DesktopAudioFileTransfer.save(server.url, emptyMap(), target, "fixture") }
                assertEquals("completed original", target.readText())
                assertFalse(File(target.parentFile, target.name + ".part").exists())
            }
        }
    }

    @Test fun serverIgnoringRangeStartsACleanFileAndForeignRedirectGetsNoCredentials() = runBlocking<Unit> {
        Server { request ->
            assertNull(request.requestHeaders.getFirst("X-Source-Key"))
            assertNull(request.requestHeaders.getFirst("Authorization"))
            request.reply(200, "new recording", etag = "\"new\"")
        }.use { destination ->
            Server { request ->
                assertEquals("private", request.requestHeaders.getFirst("X-Source-Key"))
                request.responseHeaders.add("Location", destination.url); request.sendResponseHeaders(302, -1)
            }.use { origin ->
                val target = File(temporary.newFolder(), "song.m4a")
                checkpoint(target, origin.url)
                DesktopAudioFileTransfer.save(origin.url, mapOf("X-Source-Key" to "private", "Authorization" to "Bearer secret"), target, "fixture")
                assertEquals("new recording", target.readText())
            }
        }
    }

    @Test fun cancellingABlockedReadReleasesItsWorkerAndRetainsAValidatedRetry() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val firstBytes = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        Server { request ->
            if (calls.incrementAndGet() == 1) {
                request.responseHeaders.add("ETag", "\"same\""); request.sendResponseHeaders(200, 6)
                request.responseBody.write("abc".toByteArray()); request.responseBody.flush()
                release.await(5, TimeUnit.SECONDS)
            } else request.reply(206, "def", range = "bytes 3-5/6")
        }.use { server ->
            val target = File(temporary.newFolder(), "song.flac")
            val task = async { DesktopAudioFileTransfer.save(server.url, emptyMap(), target, "fixture") { done, _ ->
                if (done >= 3) firstBytes.complete(Unit)
            } }
            try {
                withTimeout(5_000) { firstBytes.await() }
                withTimeout(2_000) { task.cancelAndJoin() }
                assertFalse(target.exists())
                assertEquals("abc", File(target.parentFile, target.name + ".part").readText())
                assertFalse(File(target.parentFile, target.name + ".checkpoint").readText().contains("private-token"))
                DesktopAudioFileTransfer.save(server.url, emptyMap(), target, "fixture")
                assertEquals("abcdef", target.readText())
            } finally { release.countDown(); task.cancelAndJoin() }
        }
    }

    @Test fun downloadNamesDistinguishRecordingSourceAndQuality() {
        val base = DesktopDownloadManager.downloadIdentity("recording", "source", "HIGH", "m4a")
        assertEquals(base, DesktopDownloadManager.downloadIdentity("recording", "source", "HIGH", "m4a"))
        assertNotEquals(base, DesktopDownloadManager.downloadIdentity("other", "source", "HIGH", "m4a"))
        assertNotEquals(base, DesktopDownloadManager.downloadIdentity("recording", "other", "HIGH", "m4a"))
        assertNotEquals(base, DesktopDownloadManager.downloadIdentity("recording", "source", "LOSSLESS", "m4a"))
    }
}
