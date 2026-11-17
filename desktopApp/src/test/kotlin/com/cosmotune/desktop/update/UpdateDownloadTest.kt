package com.cosmotune.desktop.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateDownloadTest {
    private val body = ByteArray(300_000) { (it % 251).toByte() }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/Cosmotune-windows-x64.msi") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val url = "http://downloads.test:${server.address.port}/Cosmotune-windows-x64.msi"
    private val into = File.createTempFile("update", ".msi").apply { deleteOnExit() }

    /** An address that answers nothing: no machine is on it, so a connection to it can only time out or fail. */
    private val unreachable = InetAddress.getByAddress(byteArrayOf(10, 255.toByte(), 255.toByte(), 1))

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun anUnreachableFirstAddressFallsThroughToTheNextOne() = runBlocking {
        val dns = Dns { listOf(unreachable, InetAddress.getLoopbackAddress()) }
        var last = 0f
        UpdateDownload.fetch(UpdateDownload.client(null, dns, connectTimeoutMillis = 1_000), url, into) { last = it }
        assertContentEquals(body, into.readBytes())
        assertEquals(1f, last)
    }

    @Test
    fun noReachableAddressSaysSoInPlainWords() {
        val dns = Dns { listOf(unreachable) }
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { UpdateDownload.fetch(UpdateDownload.client(null, dns, connectTimeoutMillis = 1_000), url, into) {} }
        }
        assertEquals("Couldn't reach the download server. Check your internet and try again.", failure.message)
    }
}
