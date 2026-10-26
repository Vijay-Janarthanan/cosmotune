package com.cosmotune.music.desktop

import com.cosmotune.providers.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** A separate browser process keeps its cookies, storage and page JS away from the player/providers. */
internal class DesktopProviderVerificationBrowser private constructor(private val manager: ProviderManager,
    private val provider: String, private val target: ProviderVerificationTarget, private val onFailure: () -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val root = File(manager.store.root, "auth-browser").apply { check(isDirectory || mkdirs()) }.canonicalFile
    private val profile = Files.createTempDirectory(root.toPath(), "window-").toFile().also { ownerOnly(it) }
    private val server = ServerSocket(0, 2, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).also { it.soTimeout = 500 }
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val expectedHash = manager.store.state.value.installed.first { it.manifest.name == provider }.sha256
    @Volatile private var process: Process? = null
    @Volatile private var active: java.net.Socket? = null
    private val cleaned = java.util.concurrent.CountDownLatch(1)
    private val shutdown = Thread({ close() }, "Provider browser shutdown")
    init { Runtime.getRuntime().addShutdownHook(shutdown) }

    private fun current(): Boolean {
        val snapshot = manager.store.state.value
        val pending = snapshot.auth[provider]?.get("pending") as? JsonObject
        return !closed.get() && System.currentTimeMillis() < target.expiresAt &&
            snapshot.installed.any { it.manifest.name == provider && it.enabled && it.sha256 == expectedHash } &&
            pending?.get("state") == JsonPrimitive(target.state) && pending["completed"] != JsonPrimitive(true)
    }

    private fun start() {
        scope.launch {
            try {
                val rawCommand = childCommand()
                val command = if (rawCommand.size > 2 && rawCommand[1] == "-Xmx192m") {
                    // The development/test classpath can exceed Windows' process-command limit.
                    val arguments = File(profile, "launcher.args")
                    arguments.writeText(rawCommand.drop(1).joinToString("\n") { value ->
                        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    })
                    listOf(rawCommand.first(), "@${arguments.absolutePath}")
                } else rawCommand
                val child = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                process = child
                if (closed.get()) { child.destroy(); return@launch }
                child.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(Json.encodeToString(BrowserLaunch(target, profile.absolutePath, server.localPort, token)))
                }
                launch {
                    val exit = child.waitFor()
                    if (exit != 0 && !closed.get()) SwingUtilities.invokeLater(onFailure)
                    close()
                }
                while (isActive && current()) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    active = socket
                    socket.use {
                        it.soTimeout = 5_000
                        val request = DataInputStream(it.getInputStream()).readUTF()
                        require(request.length <= 40_000)
                        val body = Json.parseToJsonElement(request).jsonObject
                        val received = (body["token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        require(MessageDigest.isEqual(token.toByteArray(), received.toByteArray()) && current())
                        if (body["closed"] == JsonPrimitive(true)) return@use
                        val result = requireNotNull((body["result"] as? JsonPrimitive)?.contentOrNull)
                        require(result.length <= 16_384)
                        require(target.completedCallback(result) != null || target.signed && result.matches(Regex("grant=[A-Za-z0-9_-]{1,8192}")))
                        val accepted = try {
                            withTimeout(25_000) { manager.completeAuthentication(provider, result, target) }
                            true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { SwingUtilities.invokeLater(onFailure); false }
                        DataOutputStream(it.getOutputStream()).apply { writeUTF(if (accepted) "ok" else "retry"); flush() }
                        if (accepted) { delay(1_000); close() }
                    }
                    active = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!closed.get()) SwingUtilities.invokeLater(onFailure) }
            finally { close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            if (!SwingUtilities.isEventDispatchThread()) cleaned.await(7, java.util.concurrent.TimeUnit.SECONDS)
            return
        }
        runCatching { server.close() }; runCatching { active?.close() }
        process?.destroy()
        scope.cancel()
        // Delete only this helper's generated profile after its process has released the files.
        Thread({
            try {
            process?.let { child -> if (!child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly().waitFor() }
            if (profile.canonicalFile.toPath().startsWith(root.toPath()) && profile.parentFile.canonicalFile == root) {
                repeat(5) {
                    if (!profile.exists()) return@Thread
                    runCatching {
                        // Files.walk does not follow profile symlinks outside this generated directory.
                        Files.walk(profile.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                    }
                    Thread.sleep(300)
                }
            }
            } finally { cleaned.countDown(); runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
        }, "Provider browser cleanup").apply { isDaemon = false; start() }
        if (!SwingUtilities.isEventDispatchThread()) cleaned.await(7, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        fun install() { ProviderVerificationWindows.launcher = { manager, provider, target, failed ->
            if (ProviderChromiumWindow.executable() == null) {
                // Keep the existing supported browser/manual recovery when no compatible engine is installed.
                check(java.awt.Desktop.isDesktopSupported()) { "No browser is available" }
                java.awt.Desktop.getDesktop().browse(java.net.URI(target.url))
                AutoCloseable { }
            } else DesktopProviderVerificationBrowser(manager, provider, target, failed).also { it.start() }
        } }

        internal fun childCommand(): List<String> {
            System.getProperty("jpackage.app-path")?.let { File(it) }?.takeIf { it.isFile }?.let {
                return listOf(it.absolutePath, "--provider-auth-browser")
            }
            ProcessHandle.current().info().command().orElse(null)?.let(::File)?.takeIf {
                it.isFile && it.name.lowercase() in setOf("cosmotune", "cosmotune.exe")
            }?.let { return listOf(it.absolutePath, "--provider-auth-browser") }
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            check(java.isFile) { "Browser helper runtime unavailable" }
            val paths = linkedSetOf<String>().apply { addAll(System.getProperty("java.class.path").split(File.pathSeparator)) }
            var loader: ClassLoader? = DesktopProviderVerificationBrowser::class.java.classLoader
            while (loader != null) {
                (loader as? URLClassLoader)?.urLs?.filter { it.protocol == "file" }?.forEach { paths += File(it.toURI()).absolutePath }
                loader = loader.parent
            }
            return listOf(java.absolutePath, "-Xmx192m", "-cp", paths.joinToString(File.pathSeparator),
                "com.cosmotune.music.desktop.MainKt", "--provider-auth-browser")
        }

        private fun ownerOnly(file: File) {
            runCatching { Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        }
    }
}

@Serializable
internal data class BrowserLaunch(val target: ProviderVerificationTarget, val profile: String, val port: Int, val token: String)
