package com.cosmotune.providers

import java.io.Closeable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface ProviderScriptSession : Closeable {
    val methods: Set<String>
    suspend fun invoke(method: String, args: JsonArray, timeoutMs: Long = 30_000): String
}

/** Only strings cross the VM boundary; extensions never receive Java/Android objects. */
internal expect suspend fun createProviderScriptSession(code: String, config: JsonObject,
    bridge: (String, String) -> String, cancelHost: () -> Unit): ProviderScriptSession

internal object ProviderScripts {
    const val MAX_RESULT_BYTES = 2 * 1024 * 1024
    fun bootstrap(): String = requireNotNull(javaClass.getResourceAsStream("/providers/sflx-bootstrap.js")) {
        "Provider compatibility resource is missing"
    }.bufferedReader().use { it.readText() }

    fun start(code: String, config: JsonObject): String {
        require(code.toByteArray(Charsets.UTF_8).size <= ProviderPackages.MAX_SCRIPT_BYTES)
        return "var __cosmotuneApi = (${bootstrap()})(__cosmotuneHost.call, $config);\n$code\n" +
            "if (!__cosmotuneApi.registered()) throw new Error('Provider did not call registerExtension');\n'ok';"
    }

    fun invoke(method: String, args: JsonArray): String {
        require(method.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,99}")))
        val arguments = JsonPrimitive(args.toString()).toString()
        require(arguments.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES)
        return """
            var __cosmotuneResult = undefined, __cosmotuneFailure = undefined;
            Promise.resolve().then(function() { return __cosmotuneApi.invoke(${JsonPrimitive(method)}, $arguments); })
              .then(function(result) { __cosmotuneResult = result; }, function(error) {
                __cosmotuneFailure = String(error && error.message || error).slice(0, 1000);
              });
            'ok';
        """.trimIndent()
    }

    fun validateResult(result: String): String {
        require(result.length <= MAX_RESULT_BYTES && result.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "Provider result exceeds 2 MB" }
        Json.parseToJsonElement(result)
        return result
    }
}
