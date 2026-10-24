package com.cosmotune.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** App-window lifetime owner. Hiding its settings does not silently start or stop an enabled remote. */
class LanRemoteController(private val host: LanRemoteHost) : AutoCloseable {
    private val lock = Any()
    private var server: LanRemoteServer? = null
    private var closed = false
    private val mutable = MutableStateFlow<LanRemoteSession?>(null)
    val session = mutable.asStateFlow()

    suspend fun addresses(): List<InetAddress> = withContext(Dispatchers.IO) { LanRemoteServer.localAddresses() }

    suspend fun start(address: InetAddress) {
        var started: LanRemoteServer? = null
        try {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    check(!closed) { "The application window has closed" }
                    check(server == null) { "Stop the current remote before changing its interface" }
                    val current = LanRemoteServer.start(host, address)
                    started = current; server = current; mutable.value = current.session
                }
            }
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (started != null && server === started) { server = null; mutable.value = null }
                started?.close()
            }
            throw e
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            server?.close(); server = null; mutable.value = null
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            server?.close(); server = null; mutable.value = null
        }
    }
}
