package com.cosmotune.providers

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Endpoint reachability and published status, never proof that a recording can be resolved or decoded. */
enum class ProviderServiceState { REACHABLE, HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }
data class ProviderServiceResult(val id: String, val label: String, val required: Boolean,
    val state: ProviderServiceState, val httpStatus: Int?, val latencyMs: Long)
data class ProviderServiceReport(val packageHash: String, val checkedAtMs: Long, val checks: List<ProviderServiceResult>)

/** Explicit UI checks use only an installed package's approved domains and no account headers/cookies. */
class ProviderServiceHealth(private val client: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val mutable = MutableStateFlow<Map<String, ProviderServiceReport>>(emptyMap())
    val reports = mutable.asStateFlow()
    private val active = ConcurrentHashMap.newKeySet<ProviderNetwork>()
    @Volatile private var closed = false

    suspend fun check(installed: ProviderInstall, force: Boolean = false): ProviderServiceReport = withContext(Dispatchers.IO) {
        check(!closed && installed.enabled) { "Enable the provider before checking its services" }
        installed.manifest.validate()
        val ttl = installed.manifest.serviceHealth.minOfOrNull { it.cacheTtlSeconds.coerceIn(60, 600) } ?: 60
        mutable.value[installed.manifest.name]?.takeIf {
            !force && it.packageHash == installed.sha256 && now() - it.checkedAtMs in 0 until ttl * 1000L
        }?.let { return@withContext it }
        val results = installed.manifest.serviceHealth.map { input ->
            currentCoroutineContext().ensureActive()
            val network = ProviderNetwork(installed.manifest.permissions, installed.allowLocalNetwork, client)
            active.add(network)
            val started = System.nanoTime()
            try {
                check(!closed)
                providerRuntimeWork(network::cancel) {
                    network.withResponse(input.url, buildJsonObject {
                        put("method", input.method.uppercase()); putJsonObject("headers") { put("Accept", "application/json") }
                    }, responseTimeoutSeconds = ((input.timeoutMs + 999) / 1000).toLong()) { reply ->
                        var size = 0
                        val output = java.io.ByteArrayOutputStream()
                        var truncated = false
                        if (!input.method.equals("HEAD", true)) reply.body.byteStream().use { stream ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (size + count > 65_536) { truncated = true; break }
                                output.write(buffer, 0, count); size += count
                            }
                        }
                        val body = if (truncated) null else output.toString(Charsets.UTF_8.name())
                        ProviderServiceResult(input.id, input.label.ifBlank { input.id }, input.required,
                            classify(reply.code, body, input.serviceKey), reply.code, (System.nanoTime() - started) / 1_000_000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                ProviderServiceResult(input.id, input.label.ifBlank { input.id }, input.required,
                    ProviderServiceState.UNKNOWN, null, (System.nanoTime() - started) / 1_000_000)
            } finally { active.remove(network); network.cancel() }
        }
        currentCoroutineContext().ensureActive()
        check(!closed)
        ProviderServiceReport(installed.sha256, now(), results).also { report ->
            synchronized(this@ProviderServiceHealth) {
                check(!closed)
                mutable.value = (mutable.value - installed.manifest.name).entries.toList().takeLast(63).associate { it.toPair() } +
                    (installed.manifest.name to report)
            }
        }
    }

    @Synchronized override fun close() { closed = true; active.forEach(ProviderNetwork::cancel); mutable.value = emptyMap() }

    internal companion object {
        fun classify(httpStatus: Int, body: String?, serviceKey: String?): ProviderServiceState {
            if (httpStatus in setOf(401, 403)) return ProviderServiceState.DEGRADED
            if (httpStatus in setOf(408, 429, 502, 503, 504)) return ProviderServiceState.UNKNOWN
            if (httpStatus !in 200..299) return ProviderServiceState.UNAVAILABLE
            if (body == null) return ProviderServiceState.UNKNOWN
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return ProviderServiceState.REACHABLE
            val service = if (serviceKey.isNullOrBlank()) root else
                ((root["services"] as? JsonObject)?.get(serviceKey) as? JsonObject ?: return ProviderServiceState.UNKNOWN)
            val status = (service["status"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            val code = (service["status"] as? JsonPrimitive)?.intOrNull
            return when {
                (service["ok"] as? JsonPrimitive)?.booleanOrNull == false -> ProviderServiceState.UNAVAILABLE
                code in setOf(401, 403) -> ProviderServiceState.DEGRADED
                code in setOf(408, 429, 500, 502, 503, 504) -> ProviderServiceState.UNKNOWN
                code != null && code >= 400 -> ProviderServiceState.UNAVAILABLE
                code != null && code in 200..299 -> ProviderServiceState.HEALTHY
                status in setOf("degraded", "partial", "warning", "warn") -> ProviderServiceState.DEGRADED
                status in setOf("down", "offline", "error", "failed", "fail", "unhealthy") -> ProviderServiceState.UNAVAILABLE
                status in setOf("ok", "up", "online", "healthy", "operational") ||
                    (service["ok"] as? JsonPrimitive)?.booleanOrNull == true -> ProviderServiceState.HEALTHY
                else -> ProviderServiceState.REACHABLE
            }
        }
    }
}
