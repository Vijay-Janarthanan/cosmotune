package com.cosmotune.providers

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.Test
import kotlinx.coroutines.*

class ProviderLoopbackAuthTest {
    private val state = "fixture-proof-0123456789abcdef"
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private fun port() = ServerSocket(0, 1, loopback).use { it.localPort }
    private fun request(port: Int, target: String, method: String = "GET", host: String = "127.0.0.1:$port", extra: String = ""): String =
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$method $target HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    private fun awaitClosed(port: Int) {
        val end = System.nanoTime() + 2_000_000_000
        while (System.nanoTime() < end) {
            if (runCatching { Socket(loopback, port).close() }.isFailure) return
            Thread.sleep(10)
        }
        fail("Provider callback listener stayed open")
    }

    @Test fun aSingleCallbackReturnsAReadablePageWithoutEchoingTheCodeAndClosesItsPort() {
        ProviderLoopbackAuth().use { callbacks ->
            val port = port(); val active = AtomicBoolean(true); val received = java.util.concurrent.ConcurrentLinkedQueue<String>()
            assertTrue(callbacks.prepare("fixture", "http://127.0.0.1:$port/callback", state, 600_000, active::get) {
                received += it; active.set(false)
            })
            val response = request(port, "/callback?code=private-code-123&state=$state")
            assertTrue(response.startsWith("HTTP/1.1 200")); assertTrue(response.contains("Sign-in received"))
            assertTrue(response.contains("Cache-Control: no-store")); assertTrue(response.contains("script-src 'sha256-"))
            assertFalse(response.contains("private-code-123")); assertFalse(response.contains(state))
            assertTrue(response.contains("history.replaceState")); assertEquals(1, received.size)
            assertTrue(received.single().endsWith("code=private-code-123&state=$state"))
            awaitClosed(port)
        }
    }

    @Test fun wrongHostsRoutesProofsDuplicateParametersAndBodiesNeverReachTheProvider() {
        ProviderLoopbackAuth().use { callbacks ->
            val port = port(); val count = AtomicInteger()
            assertTrue(callbacks.prepare("fixture", "http://127.0.0.1:$port/callback", state, 600_000, { true }) { count.incrementAndGet() })
            val valid = "/callback?code=private-code&state=$state"
            for (response in listOf(request(port, valid, host = "evil.invalid:$port"), request(port, valid, method = "POST"),
                request(port, "/wrong?code=private-code&state=$state"), request(port, "/callback?code=private-code&state=wrong"),
                request(port, "$valid&state=$state"), request(port, valid, extra = "Content-Length: 1\r\n"),
                request(port, valid, extra = "Host: 127.0.0.1:$port\r\n"))) {
                assertTrue(response.startsWith("HTTP/1.1 400")); assertFalse(response.contains("private-code"))
            }
            assertEquals(0, count.get())
            assertTrue(request(port, valid).startsWith("HTTP/1.1 200")); assertEquals(1, count.get())
        }
    }

    @Test fun cancellationExpiryAndReplacedProofCloseSocketsAndReleaseTheListenerBudget() {
        ProviderLoopbackAuth().use { callbacks ->
            repeat(24) {
                val port = port()
                assertTrue(callbacks.prepare("blocked", "http://127.0.0.1:$port/callback", state, 600_000, { true }) { fail("Unexpected callback") })
                Socket(loopback, port).use { stalled ->
                    stalled.soTimeout = 2_000
                    callbacks.stop("blocked")
                    val read = try { stalled.getInputStream().read() }
                        catch (e: java.net.SocketTimeoutException) { throw AssertionError("Cancelled callback left a blocked socket", e) }
                        catch (_: java.net.SocketException) { -1 }
                    assertEquals(-1, read)
                }
                awaitClosed(port)
            }
            val expired = port()
            assertTrue(callbacks.prepare("expired", "http://127.0.0.1:$expired/callback", state, 20, { true }) { fail("Expired callback") })
            awaitClosed(expired)
            val active = AtomicBoolean(true); val revoked = port()
            assertTrue(callbacks.prepare("revoked", "http://127.0.0.1:$revoked/callback", state, 600_000, active::get) { fail("Revoked callback") })
            active.set(false); awaitClosed(revoked)
            val ports = generateSequence(::port).distinct().take(5).toList()
            for (index in 0..3) assertTrue(callbacks.prepare("provider$index", "http://127.0.0.1:${ports[index]}/callback", state, 600_000, { true }) {})
            assertFalse(callbacks.prepare("fifth", "http://127.0.0.1:${ports[4]}/callback", state, 600_000, { true }) {})
            callbacks.stop("provider0")
            assertTrue(callbacks.prepare("fifth", "http://127.0.0.1:${ports[4]}/callback", state, 600_000, { true }) {})
        }
    }

    @Test fun unsupportedAddressesAndBusyPortsFallBackWithoutListeningOnAnotherInterface() {
        ProviderLoopbackAuth().use { callbacks ->
            for (url in listOf("https://127.0.0.1:4242/callback", "http://192.168.1.2:4242/callback",
                "http://0.0.0.0:4242/callback", "http://127.0.0.1/callback", "cosmotune://provider-auth/fixture")) {
                assertFalse(callbacks.prepare("fixture", url, state, 600_000, { true }) { fail("Unsupported callback") })
            }
            ServerSocket(0, 1, loopback).use { occupied ->
                assertFalse(callbacks.prepare("fixture", "http://127.0.0.1:${occupied.localPort}/callback", state, 600_000, { true }) {})
                assertFalse(occupied.isClosed)
            }
        }
    }

    @Test fun disconnectCancelsAnInFlightCallbackAndClosesItsBrowserSocket() = runBlocking<Unit> {
        ProviderLoopbackAuth().use { callbacks ->
            val port = port(); val entered = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
            assertTrue(callbacks.prepare("fixture", "http://127.0.0.1:$port/callback", state, 600_000, { true }) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { finished.complete(Unit) }
            })
            val response = async(Dispatchers.IO) { runCatching { request(port, "/callback?code=private-code&state=$state") }.getOrDefault("") }
            withTimeout(2_000) { entered.await() }
            callbacks.stop("fixture")
            withTimeout(2_000) { finished.await() }
            assertFalse(withTimeout(2_000) { response.await() }.contains("200 OK"))
            awaitClosed(port)
        }
    }
}
