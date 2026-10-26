package com.cosmotune.providers

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Anonymous, explicit opt-in. Records endpoint/status metadata, never challenge URLs or grants. */
class ProviderOperatorAccessLiveTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test(timeout = 150_000) fun signedOperatorsCanStartTheNativeVerificationFlow() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_OPERATOR_LIVE") == "1")
        val build = File("../build").canonicalFile
        val root = File(build, "operator-access-2026-10-26").apply { mkdirs() }
        val rows = mutableListOf<JsonObject>()
        for (provider in listOf("amazon", "qobuz-web", "tidal-web", "deezer")) {
            val packages = File(build, "provider-live-probe-2026-10-26/profiles/$provider/packages")
            val archive = requireNotNull(packages.listFiles()?.firstOrNull { it.extension == "sflx" })
            val manifest = ZipFile(archive).use { zip ->
                json.decodeFromString<ProviderManifest>(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }).validate()
            }
            rows += buildJsonObject {
                put("provider", provider); put("packageVersion", manifest.version)
                put("operator", manifest.signedSession?.get("baseUrl") ?: JsonNull)
                try {
                    ProviderManager(ProviderStore(File(root, "profiles/$provider"))).use { manager ->
                        manager.trust(manager.inspect { archive.inputStream() })
                        val authenticated = withTimeout(25_000) { manager.verifyProviderAccess(provider) }
                        val pending = manager.store.state.value.auth[provider]?.get("pending") as? JsonObject
                        put("status", if (authenticated) "SESSION_ISSUED" else "HUMAN_VERIFICATION_REQUIRED")
                        pending?.let {
                            val address = URI(it.getValue("url").jsonPrimitive.content)
                            put("challengeOrigin", "${address.scheme}://${address.rawAuthority}")
                            put("challengePath", address.path)
                            put("callbackScheme", URI(it.getValue("callback").jsonPrimitive.content).scheme)
                            manager.prepareBrowserAuthentication(provider).use { browser -> put("automaticCallback", browser.automaticCallback) }
                        }
                    }
                } catch (error: Exception) { put("status", "UNAVAILABLE"); put("errorType", error.javaClass.simpleName) }
            }
        }
        val health = mutableListOf<JsonObject>()
        for (path in listOf("/v2/health", "/v1/health")) {
            health += buildJsonObject {
                put("path", path)
                try {
                    com.cosmotune.music.data.Http.client.newCall(Request.Builder().url("https://api.zarz.moe$path").build()).execute().use { response ->
                        put("httpStatus", response.code)
                        val body = response.body.string().take(32_768)
                        val metadata = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                        val status = (metadata?.get("status") as? JsonPrimitive)?.contentOrNull
                        if (status in setOf("ok", "healthy", "operational", "degraded", "unhealthy")) put("reportedStatus", status)
                        put("responseFields", JsonArray(metadata?.keys.orEmpty().take(25).map(::JsonPrimitive)))
                        val services = metadata?.get("services") as? JsonObject
                        if (services != null) putJsonObject("serviceStatuses") {
                            services.entries.take(20).forEach { (name, value) ->
                                val state = (value as? JsonPrimitive)?.contentOrNull
                                    ?: ((value as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
                                if (name.matches(Regex("[A-Za-z0-9_-]{1,40}")) && state != null && state.matches(Regex("[A-Za-z _-]{1,40}"))) put(name, state)
                            }
                        }
                    }
                } catch (error: Exception) { put("errorType", error.javaClass.simpleName) }
            }
        }
        File(root, "results.json").writeText(buildJsonObject {
            put("scope", "Isolated native signed-session verification; no challenge completed or audio decoded")
            put("providers", JsonArray(rows)); put("health", JsonArray(health))
        }.toString())
    }
}
