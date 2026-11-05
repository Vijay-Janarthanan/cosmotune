package com.cosmotune.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable data class ProviderPermissions(val network: List<String> = emptyList(), val storage: Boolean = false,
    val file: Boolean = false, val allowHttp: Boolean = false)

@Serializable data class ProviderSetting(val key: String, val label: String = "", val type: String = "string",
    val default: JsonElement = JsonNull, val options: List<JsonElement> = emptyList(), val description: String = "",
    val secret: Boolean = false, val action: String? = null)

@Serializable data class ProviderQuality(val id: String, val label: String = "", val description: String = "", val kind: String = "")

@Serializable data class ProviderHealthCheck(val id: String, val url: String, val label: String = "",
    val method: String = "GET", val serviceKey: String? = null, val timeoutMs: Int = 4000,
    val cacheTtlSeconds: Int = 60, val required: Boolean = false)

@Serializable data class ProviderManifest(val name: String, val version: String, val displayName: String = "",
    val description: String = "", val type: List<String> = emptyList(), val permissions: ProviderPermissions = ProviderPermissions(),
    val minAppVersion: String = "0.0.0", val requiredRuntimeFeatures: List<String> = emptyList(),
    val settings: List<ProviderSetting> = emptyList(), val qualityOptions: List<ProviderQuality> = emptyList(),
    val capabilities: JsonObject = JsonObject(emptyMap()), val signedSession: JsonObject? = null,
    val urlHandler: JsonObject = JsonObject(emptyMap()), val searchBehavior: JsonObject = JsonObject(emptyMap()),
    val serviceHealth: List<ProviderHealthCheck> = emptyList()) {
    val title: String get() = displayName.ifBlank { name }
    /** A catalogue or audio package, which the Search tab asks; a lyrics-only package has no track search to ask. */
    val searchable: Boolean get() = "metadata_provider" in type || "download_provider" in type
    val trackSearchFilter: String get() = (searchBehavior["filters"] as? JsonArray).orEmpty().firstNotNullOfOrNull {
        ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id in setOf("songs", "song", "tracks", "track") }
    } ?: "tracks"
    val homeFeed: Boolean get() = capabilities["homeFeed"]?.jsonPrimitive?.booleanOrNull == true

    fun validate(): ProviderManifest = apply {
        require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}"))) { "Invalid provider identity" }
        require(version.length <= 64 && version.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][A-Za-z0-9.-]+)?"))) { "Invalid provider version" }
        require(type.isNotEmpty() && type.all { it in setOf("metadata_provider", "download_provider", "lyrics_provider") }) { "Unsupported provider type" }
        require(displayName.length <= 200 && description.length <= 8_192)
        require(permissions.network.size <= 100 && permissions.network.all {
            it.length <= 255 && it.matches(Regex("(?:\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))
        }) { "Invalid provider network permissions" }
        require(settings.size <= 100 && settings.map { it.key }.distinct().size == settings.size)
        require(settings.all { it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,99}")) && it.options.size <= 100 })
        require(qualityOptions.size <= 30 && qualityOptions.map { it.id }.distinct().size == qualityOptions.size)
        require(serviceHealth.size <= 8 && serviceHealth.map { it.id }.distinct().size == serviceHealth.size) { "Invalid service health checks" }
        serviceHealth.forEach { check ->
            require(check.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && check.label.length <= 200 &&
                check.serviceKey.orEmpty().length <= 100 && check.timeoutMs in 500..15_000 && check.cacheTtlSeconds in 1..3600)
            val url = check.url.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                check.method.uppercase() in setOf("GET", "HEAD") && permissions.network.any { domain ->
                    val allowed = domain.lowercase()
                    if (allowed.startsWith("*.")) url.host.endsWith(allowed.drop(1)) && url.host != allowed.drop(2) else url.host == allowed
                }) { "Service health requires HTTPS and an approved network domain" }
        }
        require(requiredRuntimeFeatures.size <= 50 && requiredRuntimeFeatures.all { it.matches(Regex("[A-Za-z][A-Za-z0-9]*@[1-9][0-9]?")) })
        require(signedSession != null || requiredRuntimeFeatures.none { it.startsWith("signedSession@") || it.startsWith("sessionGrant@") }) {
            "Signed-session runtime features need signedSession configuration"
        }
        ProviderSignedSession.validateConfig(this)
    }

    fun effectiveSettings(values: Map<String, JsonElement>): JsonObject = buildJsonObject {
        for (field in settings) put(field.key, values[field.key] ?: field.default)
    }
}
