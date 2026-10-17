package com.cosmotune.desktop

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.request.get
import io.ktor.http.Url

class TorRoutingTest {
    @Test fun `only exact loopback hosts bypass the tunnel`() {
        val selector = TorProxySelector(9123)
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            assertEquals(Proxy.NO_PROXY, selector.select(URI("https://$host/test")).single())
        }
        for (host in listOf("example.com", "localhost.example.com", "192.168.1.1")) {
            assertEquals(Proxy.Type.HTTP, selector.select(URI("https://$host/test")).single().type())
        }
    }

    private fun recordsConnect(request: (Int) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val observed = CompletableFuture<String>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        observed.complete(first)
                        socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (e: Exception) { observed.completeExceptionally(e) }
            }
            runCatching { request(server.localPort) }
            assertTrue(observed.get(7, TimeUnit.SECONDS).startsWith("CONNECT unavailable.invalid:443 HTTP/1."))
        }
    }

    @Test fun `Java range transport tunnels hostname without local DNS`() = recordsConnect { port ->
        HttpClient.newBuilder().proxy(TorProxySelector(port)).connectTimeout(Duration.ofSeconds(3)).build().use { client ->
            client.send(HttpRequest.newBuilder(URI("https://unavailable.invalid/song")).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    @Test fun `OkHttp transport tunnels hostname without local DNS`() = recordsConnect { port ->
        val client = okhttp3.OkHttpClient.Builder().proxySelector(TorProxySelector(port))
            .callTimeout(3, TimeUnit.SECONDS).build()
        try { client.newCall(okhttp3.Request.Builder().url("https://unavailable.invalid/song").build()).execute().close() }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun `CIO transport tunnels hostname without local DNS`() = recordsConnect { port ->
        runBlocking {
            val client = io.ktor.client.HttpClient(CIO) {
                engine { proxy = ProxyBuilder.http(Url("http://127.0.0.1:$port")); requestTimeout = 3000 }
            }
            try { client.get("https://unavailable.invalid/song") } finally { client.close() }
        }
    }

    @Test fun `failed proxy does not supply a direct route`() {
        val selector = TorProxySelector(9123)
        val uri = URI("https://example.com")
        selector.connectFailed(uri, InetSocketAddress("127.0.0.1", 9123), java.io.IOException())
        assertEquals(listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9123))), selector.select(uri))
    }
}
