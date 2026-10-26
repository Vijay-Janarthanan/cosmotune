package com.cosmotune.providers

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in comparison telemetry contains endpoint paths/statuses, never headers, bodies or session values. */
class AmazonRuntimeComparisonLiveTest {
    @Test(timeout = 180_000) fun exactAmazonPackageThroughNativeHost() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_AMAZON_COMPARISON") == "1")
        val root = File("../build/amazon-comparison").canonicalFile.apply { mkdirs() }
        val files = File(root, "package")
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderManifest>(File(files, "manifest.json").readText())
        val code = File(files, "index.js").readText()
        val events = CopyOnWriteArrayList<JsonObject>()
        val started = System.nanoTime()
        fun event(phase: String, call: Call, status: Int? = null, detail: String? = null) {
            events += buildJsonObject {
                put("phase", phase); put("endpoint", call.request().url.host + call.request().url.encodedPath)
                put("method", call.request().method); put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                status?.let { put("status", it) }; detail?.let { put("detail", it.take(200)) }
            }
        }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) = event("start", call)
            override fun dnsStart(call: Call, domainName: String) = event("dns", call)
            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = event("connect", call)
            override fun responseHeadersEnd(call: Call, response: Response) = event("headers", call, response.code, response.protocol.toString())
            override fun callEnd(call: Call) = event("end", call)
            override fun callFailed(call: Call, ioe: java.io.IOException) = event("failed", call, detail = ioe.javaClass.simpleName)
        }).build()
        val store = ProviderStore(File(root, "native-profile"))
        val install = ProviderInstall(manifest, "0".repeat(64))
        store.update { it.copy(installed = listOf(install)) }
        val network = ProviderNetwork(manifest.permissions, baseClient = client, cookieJar = ProviderCookieJar.forProvider(install, store))
        val host = ProviderMetadataHost(install, store, network)
        val settings = manifest.effectiveSettings(emptyMap())
        val config = buildJsonObject { put("permissions", Json.encodeToJsonElement(ProviderPermissions.serializer(), manifest.permissions)); put("settings", settings); put("signedSession", manifest.signedSession ?: JsonNull) }
        var status = "STARTED"
        var count: Int? = null
        var errorType: String? = null
        val bridge: (String, String) -> String = { method, input ->
            val output = if (method == "http.request" && System.getenv("COSMOTUNE_AMAZON_TRACE_WIRE") == "1") {
                val args = Json.parseToJsonElement(input).jsonArray
                try {
                    val value = network.request(args[0].jsonPrimitive.content, args[1].jsonObject)
                    val wire = buildJsonObject { put("value", value) }.toString()
                    events += buildJsonObject { put("phase", "wire-size"); put("bytes", wire.toByteArray().size) }
                    wire
                } catch (error: Exception) {
                    events += buildJsonObject { put("phase", "wire-error"); put("type", error.javaClass.simpleName) }
                    buildJsonObject { put("failure", "Diagnostic request failed") }.toString()
                }
            } else host.bridge(method, input)
            if (method == "http.request") {
                val value = (Json.parseToJsonElement(output) as? JsonObject)?.get("value") as? JsonObject
                val body = (value?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val headers = value?.get("headers") as? JsonObject
                events += buildJsonObject {
                    put("phase", "body-shape"); put("chars", body.length)
                    put("validJson", runCatching { Json.parseToJsonElement(body) }.isSuccess)
                    put("firstCodepoint", body.firstOrNull()?.code)
                    put("contentEncoding", headers?.get("content-encoding") ?: JsonNull)
                    put("contentType", headers?.get("content-type") ?: JsonNull)
                }
            }
            output
        }
        try {
            createProviderScriptSession(code, config, bridge, host::cancel).use { runtime ->
                runtime.invoke("initialize", buildJsonArray { add(settings) }, timeoutMs = 45_000)
                val result = runtime.invoke("customSearch", buildJsonArray {
                    add("Blinding Lights The Weeknd"); addJsonObject { put("limit", 20); put("filter", "songs") }
                }, timeoutMs = 75_000)
                count = ProviderCatalogue.tracks("amazon", Json.parseToJsonElement(result)).size
                status = if (count!! > 0) "SEARCH_RESULTS" else "EMPTY_SEARCH"
            }
        } catch (error: Exception) { status = "ERROR"; errorType = error.javaClass.simpleName }
        finally {
            host.cancel()
            File(root, "native-result.json").writeText(buildJsonObject {
                put("runtime", "Cosmotune native provider host"); put("status", status); put("count", count)
                put("errorType", errorType); put("events", JsonArray(events))
            }.toString())
        }
    }
}
