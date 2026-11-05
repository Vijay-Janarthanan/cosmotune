package com.cosmotune.music.desktop

import java.io.File
import java.net.URI
import java.net.InetAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Uses an installed Chromium in its own temporary profile, without a browser bundle or normal-profile access. */
internal object ProviderChromiumWindow {
    internal fun executable(): File? {
        val candidates = mutableListOf<File>()
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("windows")) {
            for (base in listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))) {
                for (path in listOf("Google/Chrome/Application/chrome.exe", "Microsoft/Edge/Application/msedge.exe", "Chromium/Application/chrome.exe")) candidates += File(base, path)
            }
        } else if (os.contains("mac")) {
            for (base in listOf(File("/Applications"), File(System.getProperty("user.home"), "Applications"))) {
                for (name in listOf("Google Chrome", "Microsoft Edge", "Chromium")) candidates += File(base, "$name.app/Contents/MacOS/$name")
            }
        } else {
            for (base in System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)) {
                for (name in listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge")) candidates += File(base, name)
            }
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun run(launch: BrowserLaunch, executable: File) {
        val profile = File(launch.profile, "chromium").apply { check(mkdirs()) }
        val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        val browser = ProcessBuilder(executable.absolutePath, "--app=about:blank", "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-port=$port", "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check",
            "--disable-extensions", "--disable-sync", "--window-size=760,860")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val shutdown = Thread({ browser.descendants().forEach { it.destroy() }; browser.destroy() }, "Provider Chromium shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        var rpc: Rpc? = null
        val trace = mutableListOf<JsonObject>()
        fun diagnostic(value: JsonObject) {
            if (System.getenv("COSMOTUNE_PROVIDER_BROWSER_TRACE") != "1") return
            synchronized(trace) {
                trace += value
                File(File(launch.profile).parentFile, "browser-diagnostics.json").writeText(JsonArray(trace.takeLast(80)).toString())
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            val localHttp = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build()
            var endpoint: URI? = null
            while (endpoint == null && System.nanoTime() < deadline && browser.isAlive) {
                endpoint = runCatching {
                    val response = localHttp.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version"))
                        .timeout(java.time.Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString())
                    require(response.statusCode() == 200 && response.body().length <= 16_384)
                    URI(Json.parseToJsonElement(response.body()).jsonObject.getValue("webSocketDebuggerUrl").jsonPrimitive.content).also {
                        require(it.scheme == "ws" && it.host == "127.0.0.1" && it.port == port && it.path.matches(Regex("/devtools/browser/[A-Za-z0-9-]{1,100}")))
                    }
                }.getOrNull()
                if (endpoint == null) Thread.sleep(100)
            }
            val target = launch.target
            val done = AtomicBoolean(false)
            val submitted = AtomicBoolean(false)
            val mainTarget = arrayOf<String?>(null)
            val sessions = ConcurrentHashMap.newKeySet<String>()
            val rootFrames = ConcurrentHashMap<String, String>()
            val operatorResponses = ConcurrentHashMap<String, JsonObject>()
            lateinit var client: Rpc
            fun submit(answer: String) {
                if (!submitted.compareAndSet(false, true)) return
                try { ProviderVerificationBrowserMain.send(launch, answer) } finally { done.set(true) }
            }
            fun attach(session: String) {
                if (!sessions.add(session)) return
                client.call("Page.enable", session = session)
                if (System.getenv("COSMOTUNE_PROVIDER_BROWSER_TRACE") == "1") client.call("Network.enable", session = session)
                val frame = client.call("Page.getFrameTree", session = session)["frameTree"]?.jsonObject?.get("frame")?.jsonObject
                frame?.get("id")?.jsonPrimitive?.contentOrNull?.let { rootFrames[session] = it }
                client.call("Fetch.enable", buildJsonObject {
                    putJsonArray("patterns") { add(buildJsonObject { put("urlPattern", "*"); put("resourceType", "Document"); put("requestStage", "Request") }) }
                }, session)
            }
            client = Rpc(requireNotNull(endpoint)) { event ->
                val method = event["method"]?.jsonPrimitive?.contentOrNull
                val params = event["params"] as? JsonObject ?: JsonObject(emptyMap())
                val session = event["sessionId"]?.jsonPrimitive?.contentOrNull
                when (method) {
                    "Network.responseReceived" -> {
                        val response = params["response"] as? JsonObject
                        val address = runCatching { URI(response?.get("url")?.jsonPrimitive?.content.orEmpty()) }.getOrNull()
                        if (address?.host == URI(target.url).host && response?.get("mimeType")?.jsonPrimitive?.contentOrNull?.contains("json") == true) {
                            operatorResponses[params.getValue("requestId").jsonPrimitive.content] = buildJsonObject {
                                put("httpStatus", response["status"] ?: JsonNull)
                                put("path", address.path.split('/').joinToString("/") { if (it.length > 30 || it.startsWith("chl_") || it.startsWith("gr_")) "<id>" else it })
                            }
                        }
                    }
                    "Network.loadingFinished" -> if (session != null) {
                        val id = params["requestId"]?.jsonPrimitive?.contentOrNull
                        val metadata = id?.let { operatorResponses.remove(it) }
                        if (metadata != null) {
                            val body = runCatching { client.call("Network.getResponseBody", buildJsonObject { put("requestId", id) }, session)
                                .get("body")?.jsonPrimitive?.contentOrNull?.takeIf { it.length < 32_768 }?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
                            val detail = listOf("error", "message", "code").mapNotNull { (body?.get(it) as? JsonPrimitive)?.contentOrNull }.joinToString(" ").lowercase()
                            diagnostic(JsonObject(metadata + mapOf("fields" to JsonArray(body?.keys.orEmpty().take(20).map(::JsonPrimitive)),
                                "terms" to JsonArray(listOf("invalid", "expired", "turnstile", "captcha", "callback", "state", "verification", "token", "browser", "origin", "rate", "limit", "failed")
                                    .filter { word -> Regex("\\b$word\\b").containsMatchIn(detail) }.map(::JsonPrimitive)))))
                        }
                    }
                    "Target.attachedToTarget" -> if (params["targetInfo"]?.jsonObject?.get("type") == JsonPrimitive("page"))
                        params["sessionId"]?.jsonPrimitive?.contentOrNull?.let(::attach)
                    "Target.targetDestroyed" -> if (params["targetId"]?.jsonPrimitive?.contentOrNull == mainTarget[0]) done.set(true)
                    "Page.windowOpen", "Page.frameRequestedNavigation" -> params["url"]?.jsonPrimitive?.contentOrNull?.let { address ->
                        target.completedCallback(address)?.let(::submit)
                    }
                    "Fetch.requestPaused" -> if (session != null) {
                        val id = params.getValue("requestId").jsonPrimitive.content
                        val address = params["request"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val callback = target.completedCallback(address)
                        val main = params["frameId"]?.jsonPrimitive?.contentOrNull == rootFrames[session]
                        if (callback != null || main && address != "about:blank" && !target.allowsNavigation(address)) {
                            client.call("Fetch.failRequest", buildJsonObject { put("requestId", id); put("errorReason", "Aborted") }, session)
                            callback?.let(::submit)
                        } else client.call("Fetch.continueRequest", buildJsonObject { put("requestId", id) }, session)
                    }
                }
            }
            rpc = client
            client.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
            val pages = client.call("Target.getTargets")["targetInfos"]?.jsonArray.orEmpty()
            val page = requireNotNull(pages.firstOrNull { it.jsonObject["type"] == JsonPrimitive("page") }).jsonObject
            mainTarget[0] = page.getValue("targetId").jsonPrimitive.content
            val session = client.call("Target.attachToTarget", buildJsonObject { put("targetId", mainTarget[0]); put("flatten", true) })
                .getValue("sessionId").jsonPrimitive.content
            attach(session)
            if (System.getenv("COSMOTUNE_PROVIDER_BROWSER_TRACE") == "1") {
                val indicator = client.call("Runtime.evaluate", buildJsonObject { put("expression", "navigator.webdriver"); put("returnByValue", true) }, session)
                    .get("result")?.jsonObject?.get("value")
                diagnostic(buildJsonObject { put("browserAutomationIndicator", indicator ?: JsonNull) })
            }
            client.call("Target.setAutoAttach", buildJsonObject { put("autoAttach", true); put("waitForDebuggerOnStart", false); put("flatten", true) })
            client.call("Browser.setDownloadBehavior", buildJsonObject { put("behavior", "deny") })
            client.call("Page.navigate", buildJsonObject { put("url", target.url) }, session)
            client.call("Page.bringToFront", session = session)
            while (!done.get() && System.currentTimeMillis() < target.expiresAt && browser.isAlive) {
                if (!submitted.get()) runCatching {
                    val value = client.call("Runtime.evaluate", buildJsonObject {
                        put("expression", com.cosmotune.providers.ProviderVerificationTarget.GRANT_SCRIPT); put("returnByValue", true)
                    }, session)["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: return@runCatching
                    val visible = Json.parseToJsonElement(value).jsonObject
                    target.verifiedPageGrant(visible.getValue("url").jsonPrimitive.content,
                        visible.getValue("text").jsonPrimitive.content)?.let(::submit)
                }
                Thread.sleep(500)
            }
        } finally {
            runCatching { rpc?.call("Browser.close") }
            rpc?.close()
            if (!browser.waitFor(2, TimeUnit.SECONDS)) { browser.descendants().forEach { it.destroy() }; browser.destroy() }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    /** Native-only CDP channel to this generated profile; no broad remote origin allowance or JS bridge. */
    internal class Rpc(address: URI, private val event: (JsonObject) -> Unit) : AutoCloseable {
        private val sequence = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val events = Executors.newSingleThreadExecutor { task -> Thread(task, "Provider browser events").apply { isDaemon = true } }
        private val sendLock = Any()
        private val socket = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
            .newWebSocketBuilder().buildAsync(address, object : WebSocket.Listener {
                private val text = StringBuilder()
                override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                    text.append(data)
                    if (text.length > 128 * 1024) { webSocket.abort(); return null }
                    if (last) {
                        val body = runCatching { Json.parseToJsonElement(text.toString()).jsonObject }.getOrNull(); text.setLength(0)
                        val id = (body?.get("id") as? JsonPrimitive)?.intOrNull
                        if (id != null) pending.remove(id)?.let { reply ->
                            if (body["error"] != null) reply.completeExceptionally(IllegalStateException("Browser operation unavailable"))
                            else reply.complete(body["result"] as? JsonObject ?: JsonObject(emptyMap()))
                        } else if (body != null) events.execute { runCatching { event(body) } }
                    }
                    webSocket.request(1)
                    return null
                }
                override fun onError(webSocket: WebSocket, error: Throwable) { pending.values.forEach { it.completeExceptionally(IllegalStateException("Browser connection closed")) } }
            }).get(10, TimeUnit.SECONDS)

        fun call(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null): JsonObject {
            val id = sequence.incrementAndGet(); val result = CompletableFuture<JsonObject>(); pending[id] = result
            val message = buildJsonObject { put("id", id); put("method", method); put("params", params); session?.let { put("sessionId", it) } }
            try {
                synchronized(sendLock) { socket.sendText(message.toString(), true).get(5, TimeUnit.SECONDS) }
                return result.get(10, TimeUnit.SECONDS)
            } finally { pending.remove(id) }
        }
        override fun close() { socket.abort(); events.shutdownNow(); pending.values.forEach { it.cancel(true) }; pending.clear() }
    }
}
