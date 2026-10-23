package com.cosmotune.desktop

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One process per user, including when the window is hidden in the tray. */
internal class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val onActivate: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    private fun listen() = thread(name = "cosmotune-activation", isDaemon = true) {
        while (!closed.get()) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 1_000
                    // Bound reads: another local process must not hold startup indefinitely.
                    val request = socket.getInputStream().readNBytes(token.length + 1)
                    if (request.contentEquals("$token\n".toByteArray(Charsets.US_ASCII))) {
                        onActivate()
                        socket.getOutputStream().write(1)
                    }
                }
            } catch (_: Exception) {
                // A timed-out/closed connection does not disable subsequent launches.
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { server.close() } finally {
            try { lock.release() } finally { channel.close() }
        }
        // Do not unlink the lock file: a waiting process may already have it open.
    }

    companion object {
        fun stateDirectory(): Path = Path.of(
            System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".local", "state").toString(),
            "cosmotune",
        )

        /** Returns a lifetime lease for the first process, null after restoring that process. */
        fun acquire(directory: Path = stateDirectory(), onActivate: () -> Unit): DesktopSingleInstance? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(directory.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val endpoint = directory.resolve("instance.endpoint")
            val deadline = System.nanoTime() + 8_000_000_000L
            try {
                while (true) {
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) {
                        var server: ServerSocket? = null
                        try {
                            server = ServerSocket().apply {
                                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                            }
                            val token = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                            // Separate from the locked file: Windows forbids reading its locked region.
                            Files.writeString(endpoint, "${server.localPort}\n$token\n")
                            return DesktopSingleInstance(channel, lock, server, token, onActivate).also { it.listen() }
                        } catch (error: Throwable) {
                            server?.close()
                            lock.release()
                            throw error
                        }
                    }
                    if (activate(endpoint)) {
                        channel.close()
                        return null
                    }
                    check(System.nanoTime() < deadline) {
                        "Cosmotune is already running but could not restore its window. Quit it from the tray and try again."
                    }
                    Thread.sleep(100)
                }
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        private fun activate(endpoint: Path): Boolean = runCatching {
            if (Files.size(endpoint) > 128) return false
            val lines = Files.readAllLines(endpoint)
            val port = lines.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val token = lines.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: return false
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 1_000
                socket.getOutputStream().write("$token\n".toByteArray(Charsets.US_ASCII))
                socket.getInputStream().read() == 1
            }
        }.getOrDefault(false)
    }
}
