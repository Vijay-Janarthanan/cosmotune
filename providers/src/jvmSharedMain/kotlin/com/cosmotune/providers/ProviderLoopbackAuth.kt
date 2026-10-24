package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Started only by the native user's Open sign-in page action, on the exact pending callback. */
internal class ProviderLoopbackAuth : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = LinkedHashMap<String, Listener>()
    private var closed = false

    fun prepare(name: String, redirect: String, state: String, remainingMs: Long,
        current: () -> Boolean, complete: suspend (String) -> Unit): Boolean {
        val uri = runCatching { URI(redirect) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (!uri.scheme.equals("http", true) || uri.port !in 1024..65535 || uri.userInfo != null || uri.fragment != null ||
            host !in setOf("localhost", "127.0.0.1", "[::1]", "::1")) return false
        require(state.length in 16..512 && remainingMs in 1..600_000)
        val address = if (host.contains(':')) InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
            else InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        synchronized(listeners) {
            check(!closed) { "Provider authentication is closed" }
            listeners.remove(name)?.close()
            listeners.entries.removeAll { it.value.stopped.get() }
            if (listeners.size >= 4 || !current()) return false
            val server = ServerSocket()
            try {
                server.reuseAddress = false
                server.bind(InetSocketAddress(address, uri.port), 4)
                server.soTimeout = 500
            } catch (_: java.io.IOException) { server.close(); return false }
            val listener = Listener(server, uri, state, remainingMs, current, complete)
            listeners[name] = listener
            listener.start()
            return true
        }
    }

    fun stop(name: String, state: String? = null) { synchronized(listeners) {
        if (state == null || listeners[name]?.state == state) listeners.remove(name)?.close()
    } }

    override fun close() {
        synchronized(listeners) {
            closed = true
            listeners.values.forEach { it.close() }; listeners.clear()
        }
        scope.cancel()
    }

    private inner class Listener(val server: ServerSocket, val expected: URI, val state: String,
        remainingMs: Long, val current: () -> Boolean, val complete: suspend (String) -> Unit) : AutoCloseable {
        val stopped = AtomicBoolean(false)
        val active = AtomicReference<Socket?>()
        private val deadline = System.nanoTime() + remainingMs * 1_000_000
        private var worker: Job? = null
        fun start() { worker = scope.launch {
            try {
                while (isActive && !stopped.get() && System.nanoTime() < deadline && current()) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    active.set(socket)
                    // stop() can close the listener between accept() and publishing this socket.
                    // A cancelled coroutine does not interrupt a blocking Socket read on its own.
                    if (stopped.get() || !isActive) {
                        runCatching { socket.close() }
                        active.compareAndSet(socket, null)
                        break
                    }
                    val watchdog = scope.launch {
                        delay(minOf(6_000, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
                        runCatching { socket.close() }
                    }
                    try {
                        socket.use {
                            it.soTimeout = 2_500
                            val input = readRequest(it)
                            if (input == null || !current() || System.nanoTime() >= deadline) respond(it, false)
                            else {
                                val accepted = try { withTimeout(5_000) { complete(input) }; true }
                                    catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e; false }
                                    catch (_: Exception) { false }
                                respond(it, accepted)
                                if (accepted) break
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* A failed/stale browser callback never leaks its URL into logs. */ }
                    finally { active.compareAndSet(socket, null); watchdog.cancel() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Closed socket, expired proof or unavailable storage: native recovery remains. */ }
            finally { close() }
        } }

        private fun readRequest(socket: Socket): String? {
            val input = socket.getInputStream()
            val bytes = ByteArrayOutputStream()
            val until = System.nanoTime() + 3_000_000_000
            var suffix = 0
            while (bytes.size() < 24 * 1024 && System.nanoTime() < until) {
                val byte = input.read()
                if (byte < 0 || byte > 127 || byte == 0) return null
                bytes.write(byte)
                suffix = (suffix shl 8) or byte
                if (suffix == 0x0d0a0d0a) break
            }
            if (suffix != 0x0d0a0d0a) return null
            val lines = bytes.toString("US-ASCII").split("\r\n")
            val request = lines.first().split(' ')
            if (request.size != 3 || request[0] != "GET" || request[2] !in setOf("HTTP/1.0", "HTTP/1.1") ||
                request[1].length > 16_384 || !request[1].startsWith('/') || request[1].startsWith("//")) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1).filter(String::isNotEmpty)) {
                if (':' !in line) return null
                val key = line.substringBefore(':').lowercase(Locale.ROOT)
                if (key in headers || key.any { it <= ' ' }) return null
                headers[key] = line.substringAfter(':').trim()
            }
            if (!headers["host"].equals(expected.rawAuthority, true) || "transfer-encoding" in headers ||
                headers["content-length"]?.let { it != "0" } == true) return null
            val received = runCatching { URI("http://${expected.rawAuthority}${request[1]}") }.getOrNull() ?: return null
            if (received.rawFragment != null || received.rawPath != expected.rawPath.orEmpty().ifBlank { "/" }) return null
            val parameters = LinkedHashMap<String, String>()
            for (item in received.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
                val key = URLDecoder.decode(item.substringBefore('='), "UTF-8")
                if (key in parameters) return null
                parameters[key] = URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
            }
            if (parameters["state"] != state) return null
            return received.toASCIIString()
        }

        private fun respond(socket: Socket, accepted: Boolean) {
            val message = if (accepted) "Sign-in received. Return to Cosmotune to finish this provider's setup."
                else "This sign-in was not accepted. Return to Cosmotune and retry or use its manual callback field."
            val cleanup = "history.replaceState(null,'',location.pathname)"
            val scriptHash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(cleanup.toByteArray(Charsets.UTF_8)))
            val body = ("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>Cosmotune sign-in</title>" +
                "<style>body{margin:0;background:#17120e;color:#f8eee6;font:16px/1.6 system-ui,sans-serif}main{max-width:480px;margin:12vh auto;padding:32px}h1{color:#ffb98f}p{margin-top:24px}</style>" +
                "<main><h1>Cosmotune</h1><p>$message</p><p>You can close this tab.</p></main><script>$cleanup</script>").toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 ${if (accepted) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-$scriptHash'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { server.close() }
            runCatching { active.getAndSet(null)?.close() }
            worker?.cancel()
        }
    }
}

class ProviderBrowserAuthentication(val url: String, val automaticCallback: Boolean,
    private val cancel: () -> Unit = {}) : AutoCloseable {
    override fun close() = cancel()
    override fun toString() = "ProviderBrowserAuthentication(automaticCallback=$automaticCallback)"
}
