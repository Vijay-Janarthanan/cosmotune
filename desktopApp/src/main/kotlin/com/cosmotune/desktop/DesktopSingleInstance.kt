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

/**
 * One process per user, including when the window is hidden in the tray. A later launch restores the first one's window and hands
 * it the link it was opened with, if any (a Watch install link the system opened Cosmotune with): after the token line it sends
 * one more line, at most [MAX_LINK] long, which the first process reads after acknowledging, so an older first process that reads
 * the token only still answers. On Windows the later launch first lets the first process take the foreground (Windows lets only
 * the app the person just started bring a window to the front), so the window comes up in front rather than flashing in the
 * taskbar.
 *
 * A first process that holds the lock but does not answer (stuck, or its files replaced under it by an update) used to make the
 * later launch throw, which the Windows launcher shows as a bare "Failed to launch JVM" box. Now the later launch asks
 * ([acquire]'s onStuck) and otherwise ends quietly.
 */
internal class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val onActivate: () -> Unit,
    private val onLink: (String) -> Unit,
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
                        // An Error too (a class missing after an update replaced the files): the next launch must still be answered.
                        try { onActivate() } catch (failure: Throwable) { if (failure is VirtualMachineError) throw failure; return@use }
                        socket.getOutputStream().write(1)
                        socket.getOutputStream().flush()
                        // A later launch may add the link it was opened with on one more line.
                        val link = runCatching { socket.getInputStream().readNBytes(MAX_LINK + 1) }.getOrNull()
                            ?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull()?.trim()
                        if (!link.isNullOrEmpty() && link.length <= MAX_LINK) runCatching { onLink(link) }
                    }
                }
            } catch (failure: Throwable) {
                // A timed-out/closed connection, or anything else one launch brings, does not disable subsequent launches.
                if (failure is VirtualMachineError) throw failure
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
        /** The longest link a later launch hands over (a plugin pack link fits). */
        const val MAX_LINK = 8_192

        fun stateDirectory(): Path = Path.of(
            System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".local", "state").toString(),
            "cosmotune",
        )

        /**
         * Returns a lifetime lease for the first process, null after restoring that process. [link] is handed to the first process
         * when this one is not it; the first process gets the links of later launches through [onLink]. When the first process
         * has not answered for [stuckAfterMs], [onStuck] is asked once with its process id (null when unknown): true when it was
         * closed, so this launch waits for the lock again and becomes the first; false ends this launch (null), without throwing.
         */
        fun acquire(
            directory: Path = stateDirectory(),
            link: String? = null,
            onLink: (String) -> Unit = {},
            stuckAfterMs: Long = 8_000L,
            onStuck: (pid: Long?) -> Boolean = { false },
            onActivate: () -> Unit,
        ): DesktopSingleInstance? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(directory.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val endpoint = directory.resolve("instance.endpoint")
            var deadline = System.nanoTime() + stuckAfterMs * 1_000_000L
            var asked = false
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
                            Files.writeString(endpoint, "${server.localPort}\n$token\n${ProcessHandle.current().pid()}\n")
                            return DesktopSingleInstance(channel, lock, server, token, onActivate, onLink).also { it.listen() }
                        } catch (error: Throwable) {
                            server?.close()
                            lock.release()
                            throw error
                        }
                    }
                    val handOver = link?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK && it.none { c -> c == '\n' || c == '\r' } }
                    if (activate(endpoint, handOver)) {
                        channel.close()
                        return null
                    }
                    if (System.nanoTime() >= deadline) {
                        if (asked || !onStuck(ownerPid(endpoint))) {
                            channel.close()
                            return null
                        }
                        asked = true
                        deadline = System.nanoTime() + stuckAfterMs * 1_000_000L
                    }
                    Thread.sleep(100)
                }
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        /** The first process's id, as it wrote it beside its port (older versions did not). */
        private fun ownerPid(endpoint: Path): Long? = runCatching {
            if (Files.size(endpoint) > 128) return null
            Files.readAllLines(endpoint).getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 && it != ProcessHandle.current().pid() }
        }.getOrNull()

        private fun activate(endpoint: Path, link: String?): Boolean = runCatching {
            if (Files.size(endpoint) > 128) return false
            val lines = Files.readAllLines(endpoint)
            val port = lines.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val token = lines.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: return false
            ownerPid(endpoint)?.let(WindowsForeground::allow)
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 1_000
                val out = socket.getOutputStream()
                out.write("$token\n".toByteArray(Charsets.US_ASCII))
                if (link != null) out.write("$link\n".toByteArray(Charsets.UTF_8))
                out.flush()
                socket.shutdownOutput()
                socket.getInputStream().read() == 1
            }
        }.getOrDefault(false)
    }
}

/**
 * Windows hands the foreground only to the app the person last started: this launch passes that on to the first process, so its
 * window can come to the front. Nothing elsewhere, and nothing if the call is unavailable.
 */
private object WindowsForeground {
    private interface User32 : com.sun.jna.Library {
        @Suppress("FunctionName") fun AllowSetForegroundWindow(processId: Int): Boolean
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) null
        else runCatching { com.sun.jna.Native.load("user32", User32::class.java) }.getOrNull()
    }

    fun allow(pid: Long) {
        runCatching { user32?.AllowSetForegroundWindow(pid.toInt()) }
    }
}
