package com.cosmotune.desktop

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An app-owned Tor client. Routing is selected before any network clients are constructed. */
object TorConnection {
    private val preferences by lazy { Preferences.userRoot().node("com.cosmotune.desktop/tor") }
    private val selected = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = selected.asStateFlow()
    private val progress = MutableStateFlow("Off")
    val status = progress.asStateFlow()
    var active = false
        private set
    private var port = 0
    @Volatile private var process: Process? = null
    val proxyUrl: String? get() = if (active) "http://127.0.0.1:$port" else null

    private val nativeCaFile: File by lazy {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val certificates = factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
        check(certificates.isNotEmpty())
        File(System.getProperty("user.home"), ".local/share/cosmotune/tor/java-ca.pem").apply {
            parentFile.mkdirs()
            writeText(certificates.joinToString("\n") {
                "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded) + "\n-----END CERTIFICATE-----"
            })
        }
    }

    fun configureNative(options: org.bytedeco.ffmpeg.avutil.AVDictionary, url: String) {
        val proxy = proxyUrl ?: return
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme == null || scheme == "file" || File(url).isFile) return
        require(scheme == "https") { "Tor mode supports HTTPS media only" }
        org.bytedeco.ffmpeg.global.avutil.av_dict_set(options, "http_proxy", proxy, 0)
        org.bytedeco.ffmpeg.global.avutil.av_dict_set(options, "tls_verify", "1", 0)
        org.bytedeco.ffmpeg.global.avutil.av_dict_set(options, "ca_file", nativeCaFile.absolutePath, 0)
    }

    fun select(enabled: Boolean) {
        preferences.putBoolean("enabled", enabled)
        preferences.flush()
        selected.value = enabled
    }

    fun install() {
        active = selected.value
        if (!active) return
        progress.value = "Starting Tor…"
        // Keep routing fixed for this process: pooled connections must not survive a mode change.
        port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        ProxySelector.setDefault(TorProxySelector(port))
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", "127.0.0.1")
            System.setProperty("$scheme.proxyPort", port.toString())
        }
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
        thread(name = "cosmotune-tor", isDaemon = true) {
            runCatching { runTor() }.onFailure {
                progress.value = "Tor unavailable. Turn this mode off and restart to use a direct connection."
                process?.destroy()
            }
        }
    }

    private fun runTor() {
        val root = File(System.getProperty("user.home"), ".local/share/cosmotune/tor/15.0.25")
        val resources = TorConnection::class.java.classLoader
        val manifest = resources.getResourceAsStream("tor-bundle/files.txt")
            ?: error("Tor bundle is missing")
        val files = manifest.bufferedReader().use { it.readLines().filter(String::isNotBlank) }
        root.mkdirs()
        for (name in files) {
            val target = File(root, name).canonicalFile
            require(target.toPath().startsWith(root.canonicalFile.toPath()))
            target.parentFile.mkdirs()
            // Refresh from the packaged, build-time verified bundle; never trust an old executable.
            resources.getResourceAsStream("tor-bundle/$name")!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        val executable = File(root, if (System.getProperty("os.name").startsWith("Windows")) "tor/tor.exe" else "tor/tor")
        check(executable.setExecutable(true, true) || executable.canExecute())
        val data = File(root.parentFile, "state").apply { mkdirs() }
        val config = File(data, "torrc")
        config.writeText("""
            ClientOnly 1
            SocksPort 0
            HTTPTunnelPort 127.0.0.1:$port
            DataDirectory "${data.absolutePath.replace('\\', '/')}"
            Log notice stdout
            SafeLogging 1
        """.trimIndent())
        val child = ProcessBuilder(executable.absolutePath, "-f", config.absolutePath)
            .directory(executable.parentFile).redirectErrorStream(true).start()
        process = child
        var ready = false
        val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
        thread(name = "cosmotune-tor-timeout", isDaemon = true) {
            while (child.isAlive && System.nanoTime() < deadline && progress.value != "Connected through Tor") {
                Thread.sleep(500)
            }
            if (child.isAlive && progress.value != "Connected through Tor") child.destroy()
        }
        child.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val percent = Regex("Bootstrapped (\\d+)%").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (percent != null && !ready) progress.value = "Connecting to Tor · $percent%"
                if (percent == 100 && !ready) {
                    ready = true
                    progress.value = "Checking Tor connection…"
                    val client = HttpClient.newBuilder().proxy(TorProxySelector(port))
                        .connectTimeout(Duration.ofSeconds(30)).build()
                    try {
                        val result = client.send(
                            HttpRequest.newBuilder(URI("https://check.torproject.org/api/ip"))
                                .timeout(Duration.ofSeconds(45)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        check(result.statusCode() == 200 && Regex("\"IsTor\"\\s*:\\s*true").containsMatchIn(result.body()))
                        progress.value = "Connected through Tor"
                    } finally { client.close() }
                }
            }
        }
        child.waitFor()
        error("Tor stopped")
    }
}

internal class TorProxySelector(private val port: Int) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val local = uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        return listOf(if (local) Proxy.NO_PROXY else Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
    }
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
}
