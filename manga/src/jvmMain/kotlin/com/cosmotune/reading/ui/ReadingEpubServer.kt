package com.cosmotune.reading.ui

import com.cosmotune.reading.data.ReadingDocument
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** Loopback-only capability URL. There is no filesystem route or proxy route. */
internal class ReadingEpubServer(document: ReadingDocument.Epub) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()
    val content = ReadingEpubContent(document, "http://127.0.0.1:${server.localPort}/$token/")
    private val workers = Executors.newFixedThreadPool(3) { runnable -> Thread(runnable, "reading-resource").apply { isDaemon = true } }
    @Volatile private var closed = false
    init {
        Thread({
            while (!closed) {
                try { val socket = server.accept(); workers.execute { respond(socket) } }
                catch (_: Exception) { if (!closed) close() }
            }
        }, "reading-loopback").apply { isDaemon = true; start() }
    }
    private fun respond(socket: Socket) = socket.use {
        socket.soTimeout = 3000
        try {
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine()?.takeIf { it.length < 8192 } ?: return@use
            val parts = request.split(' ')
            var headerBytes = 0
            while (true) {
                val line = reader.readLine() ?: return@use
                headerBytes += line.length
                if (headerBytes > 16384) return@use
                if (line.isEmpty()) break
            }
            val path = parts.getOrNull(1).orEmpty()
            val href = if (parts.firstOrNull() == "GET" && path.startsWith("/$token/")) content.href(content.origin + path.removePrefix("/$token/")) else null
            val resource = href?.let(content::resource)
            val body = resource?.second ?: ByteArray(0)
            val header = "HTTP/1.1 ${if (resource != null) "200 OK" else "404 Not Found"}\r\nContent-Type: ${resource?.first ?: "text/plain"}\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        } catch (_: Exception) { /* A cancelled WebView request may close its socket early. */ }
    }
    override fun close() {
        closed = true
        runCatching { server.close() }
        workers.shutdownNow()
    }
}
