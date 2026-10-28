package com.cosmotune.music.desktop

import com.cosmotune.manga.data.*
import java.io.File
import java.net.*
import java.net.http.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Dedicated installed Chromium profile; no source script runs in the native process. */
internal object DesktopMangaVerificationBrowser {
    fun install() { MangaVerificationWindows.launcher = { request -> open(request) } }

    private suspend fun open(request: MangaVerificationRequest): MangaVerificationBrowser = withContext(Dispatchers.IO) {
        MangaVerificationSessions.checked(request.address)
        require(System.currentTimeMillis() < request.expiresAt) { "Source verification expired." }
        val executable = ProviderChromiumWindow.executable() ?: error("Install Chrome, Edge or Chromium to verify Manga sources in an isolated browser.")
        val profile = Files.createTempDirectory("cosmotune-manga-verification-").toFile().canonicalFile
        val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        var process: Process? = null
        var rpc: ProviderChromiumWindow.Rpc? = null
        val closed = AtomicBoolean(false)
        val expiry = java.util.Timer("Manga verification expiry", true)
        fun cleanup() {
            if (!closed.compareAndSet(false, true)) return
            expiry.cancel()
            rpc?.close()
            process?.let { browser ->
                runCatching { browser.descendants().forEach { it.destroy() }; browser.destroy() }
                runCatching { if (!browser.waitFor(2, TimeUnit.SECONDS)) browser.destroyForcibly() }
            }
            // Only this freshly created directory, without following links, is eligible for cleanup.
            if (profile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile && profile.name.startsWith("cosmotune-manga-verification-")) {
                runCatching { Files.walk(profile.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            }
        }
        val shutdown = Thread({ cleanup() }, "Manga verification shutdown")
        try {
            process = ProcessBuilder(executable.absolutePath, "--app=about:blank", "--user-data-dir=${profile.path}",
                "--remote-debugging-port=$port", "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check",
                "--disable-extensions", "--disable-sync", "--window-size=760,860")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            Runtime.getRuntime().addShutdownHook(shutdown)
            val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var endpoint: URI? = null
            while (endpoint == null && System.nanoTime() < deadline && requireNotNull(process).isAlive) {
                currentCoroutineContext().ensureActive()
                endpoint = runCatching {
                    val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version")).timeout(java.time.Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString())
                    require(response.statusCode() == 200 && response.body().length <= 16_384)
                    URI(Json.parseToJsonElement(response.body()).jsonObject.getValue("webSocketDebuggerUrl").jsonPrimitive.content).also {
                        require(it.scheme == "ws" && it.host == "127.0.0.1" && it.port == port && it.path.matches(Regex("/devtools/browser/[A-Za-z0-9-]{1,100}")))
                    }
                }.getOrNull()
                if (endpoint == null) delay(100)
            }
            val client = ProviderChromiumWindow.Rpc(endpoint ?: error("The isolated browser could not start.")) { }
            rpc = client
            val page = client.call("Target.getTargets").getValue("targetInfos").jsonArray.first { it.jsonObject["type"] == JsonPrimitive("page") }.jsonObject
            val session = client.call("Target.attachToTarget", buildJsonObject { put("targetId", page.getValue("targetId")); put("flatten", true) }).getValue("sessionId").jsonPrimitive.content
            client.call("Browser.setDownloadBehavior", buildJsonObject { put("behavior", "deny") })
            client.call("Page.navigate", buildJsonObject { put("url", request.address) }, session)
            client.call("Page.bringToFront", session = session)
            expiry.schedule(object : java.util.TimerTask() { override fun run() { cleanup() } },
                (request.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            object : MangaVerificationBrowser {
                override suspend fun complete(): MangaBrowserProof = withContext(Dispatchers.IO) {
                    require(!closed.get() && System.currentTimeMillis() < request.expiresAt) { "Source verification expired. Open it again." }
                    val current = client.call("Page.getFrameTree", session = session).getValue("frameTree").jsonObject.getValue("frame").jsonObject.getValue("url").jsonPrimitive.content.substringBefore('#')
                    require(MangaVerificationSessions.origin(MangaVerificationSessions.checked(current)) == MangaVerificationSessions.origin(MangaVerificationSessions.checked(request.address))) { "Return to the source website before using its session." }
                    val cookies = client.call("Network.getCookies", buildJsonObject { putJsonArray("urls") { add(request.address) } }, session).getValue("cookies").jsonArray
                    require(cookies.size in 1..128) { "Complete the source check before using its session." }
                    val header = cookies.joinToString("; ") { cookie -> cookie.jsonObject.let { it.getValue("name").jsonPrimitive.content + "=" + it.getValue("value").jsonPrimitive.content } }
                    require(header.length <= 32_768)
                    val ua = client.call("Browser.getVersion").getValue("userAgent").jsonPrimitive.content
                    MangaBrowserProof(current, header, ua)
                }
                override fun close() { cleanup(); runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
            }
        } catch (failure: Throwable) {
            cleanup(); runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }; throw failure
        }
    }
}
