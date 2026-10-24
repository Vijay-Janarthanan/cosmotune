package com.cosmotune.providers

import java.net.Socket
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import kotlin.test.*

class ProviderMediaProxyTest {
    @Test fun revokingBlockedLocalWritersFreesEveryRelayWorker() {
        MockWebServer().use { server ->
            val network = transport(server)
            ProviderMediaProxy().use { proxy ->
                val payload = ByteArray(8 * 1024 * 1024) { 7 }
                repeat(4) { server.enqueue(MockResponse().setBody(okio.Buffer().write(payload))) }
                val url = java.net.URI(proxy.register("blocked", server.url("/large").toString(), JsonObject(emptyMap()), network))
                val clients = mutableListOf<Socket>()
                try {
                    repeat(4) {
                        val socket = Socket().apply { receiveBufferSize = 1024; connect(java.net.InetSocketAddress("127.0.0.1", url.port)); soTimeout = 3_000 }
                        clients += socket
                        socket.getOutputStream().write("GET ${url.path} HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\n\r\n".toByteArray())
                        val input = socket.getInputStream(); var suffix = 0; var length = 0
                        while (suffix != 0x0d0a0d0a) {
                            val byte = input.read(); assertTrue(byte >= 0 && ++length <= 16_384)
                            suffix = (suffix shl 8) or byte
                        }
                    }
                    assertEquals(4, server.requestCount)
                    proxy.revoke("blocked")
                    server.enqueue(MockResponse().setBody("fresh"))
                    val fresh = transportForExistingServer(server)
                    val next = proxy.register("fresh", server.url("/fresh").toString(), JsonObject(emptyMap()), fresh)
                    val client = OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                    client.newCall(Request.Builder().url(next).build()).execute().use { assertEquals("fresh", it.body.string()) }
                    assertEquals(5, server.requestCount)
                } finally { clients.forEach { it.close() } }
            }
        }
    }

    private var serverTrust: HandshakeCertificates? = null
    private fun transportForExistingServer(server: MockWebServer): ProviderNetwork {
        val trust = requireNotNull(serverTrust)
        return ProviderNetwork(ProviderPermissions(listOf("localhost")), true,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }

    private fun transport(server: MockWebServer): ProviderNetwork {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        serverTrust = trust
        return ProviderNetwork(ProviderPermissions(listOf("localhost")), true,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @Test fun mediaIsStreamedWithRangesAndCredentialsStayBehindLoopback() {
        MockWebServer().use { server ->
            val network = transport(server)
            ProviderMediaProxy().use { proxy ->
                val url = proxy.register("source", server.url("/song?secret-signed-value").toString(), buildJsonObject { put("Authorization", "provider-only") }, network)
                assertFalse(url.contains("secret-signed-value"))
                server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 3-5/6")
                    .addHeader("Content-Type", "audio/mp4").addHeader("Set-Cookie", "secret=value").setBody("def"))
                OkHttpClient().newCall(Request.Builder().url(url).header("Range", "bytes=3-").build()).execute().use { reply ->
                    assertEquals(206, reply.code)
                    assertEquals("def", reply.body.string())
                    assertNull(reply.header("Set-Cookie"))
                    assertEquals("bytes 3-5/6", reply.header("Content-Range"))
                }
                val actual = server.takeRequest()
                assertEquals("bytes=3-", actual.getHeader("Range"))
                assertEquals("provider-only", actual.getHeader("Authorization"))
                assertEquals("identity", actual.getHeader("Accept-Encoding"))
                proxy.revoke("source")
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { assertEquals(502, it.code) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun aReboundHostUnknownTokenAndMultipleRangeRequestCannotReachTheProvider() {
        MockWebServer().use { server ->
            val network = transport(server)
            ProviderMediaProxy().use { proxy ->
                val url = java.net.URI(proxy.register("source", server.url("/song").toString(), JsonObject(emptyMap()), network))
                for ((path, host, range) in listOf(Triple(url.path, "attacker.example", "bytes=0-"),
                    Triple("/00000000000000000000000000000000/media", "127.0.0.1:${url.port}", "bytes=0-"),
                    Triple(url.path, "127.0.0.1:${url.port}", "bytes=0-1,3-4"))) {
                    Socket("127.0.0.1", url.port).use { socket ->
                        socket.soTimeout = 5_000
                        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nRange: $range\r\n\r\n".toByteArray())
                        assertTrue(socket.getInputStream().bufferedReader().readLine().contains("502"))
                    }
                }
                assertEquals(0, server.requestCount)
            }
        }
    }
}
