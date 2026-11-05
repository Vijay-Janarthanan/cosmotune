package com.cosmotune.providers

import java.time.Instant
import kotlinx.serialization.json.*

/** Local connection state only. A service failure never changes the user's enable choice. */
enum class ProviderAccessState { DISABLED, READY, CONNECTED, RENEWAL_REQUIRED, VERIFICATION_REQUIRED, CONFIGURATION_CHANGED }

fun providerAccessState(install: ProviderInstall, auth: JsonObject?, nowMs: Long = System.currentTimeMillis()): ProviderAccessState {
    if (!install.enabled) return ProviderAccessState.DISABLED
    val record = auth ?: JsonObject(emptyMap())
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val config = install.manifest.signedSession
    if (config != null) {
        val session = record["signedSession"] as? JsonObject ?: JsonObject(emptyMap())
        if (session.isNotEmpty() && session.text("scope") != ProviderSignedSession.scopeFor(config)) return ProviderAccessState.CONFIGURATION_CHANGED
        val saved = session.text("session_id").isNotBlank() && session.text("session_secret").isNotBlank()
        val expiry = runCatching { Instant.parse(session.text("expires_at")).toEpochMilli() }.getOrDefault(0)
        if (saved && expiry > nowMs && session["blocked"] != JsonPrimitive(true)) return ProviderAccessState.CONNECTED
        if (session["blocked"] == JsonPrimitive(true) || record["pending"] is JsonObject) return ProviderAccessState.VERIFICATION_REQUIRED
        return if (saved) ProviderAccessState.RENEWAL_REQUIRED else ProviderAccessState.READY
    }
    if (record["pending"] is JsonObject) return ProviderAccessState.VERIFICATION_REQUIRED
    if (record.text("access_token").isNotBlank()) {
        val expiry = (record["expires_at"] as? JsonPrimitive)?.longOrNull
        return if (expiry == null || expiry > nowMs) ProviderAccessState.CONNECTED else ProviderAccessState.RENEWAL_REQUIRED
    }
    return if (record.text("refresh_token").isNotBlank()) ProviderAccessState.RENEWAL_REQUIRED else ProviderAccessState.READY
}
