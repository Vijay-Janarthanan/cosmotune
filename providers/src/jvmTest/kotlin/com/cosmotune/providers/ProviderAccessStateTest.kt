package com.cosmotune.providers

import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderAccessStateTest {
    private val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider")), "0".repeat(64))

    @Test fun expiredTokensRemainRenewableAndDoNotDisableTheProvider() {
        val auth = buildJsonObject { put("access_token", "old"); put("refresh_token", "renew"); put("expires_at", 1_000) }
        assertEquals(ProviderAccessState.RENEWAL_REQUIRED, providerAccessState(install, auth, 2_000))
        assertTrue(install.enabled)
        assertEquals(ProviderAccessState.DISABLED, providerAccessState(install.copy(enabled = false), auth, 2_000))
        assertEquals(ProviderAccessState.CONNECTED, providerAccessState(install, auth, 500))
    }

    @Test fun verificationAndSessionExpiryAreDifferentFromTheEnableChoice() {
        val signed = install.copy(manifest = install.manifest.copy(signedSession = JsonObject(emptyMap())))
        val auth = buildJsonObject { putJsonObject("signedSession") {
            put("scope", ProviderSignedSession.scopeFor(requireNotNull(signed.manifest.signedSession)))
            put("session_id", "id"); put("session_secret", "secret"); put("expires_at", "2026-11-02T00:00:00Z")
        } }
        assertEquals(ProviderAccessState.CONNECTED, providerAccessState(signed, auth, 1))
        assertEquals(ProviderAccessState.RENEWAL_REQUIRED, providerAccessState(signed, auth, Long.MAX_VALUE))
        val blocked = JsonObject(auth + ("signedSession" to JsonObject(auth.getValue("signedSession").jsonObject + ("blocked" to JsonPrimitive(true)))))
        assertEquals(ProviderAccessState.VERIFICATION_REQUIRED, providerAccessState(signed, blocked, 1))
        assertEquals(ProviderAccessState.DISABLED, providerAccessState(signed.copy(enabled = false), blocked, 1))
        assertEquals(ProviderAccessState.READY, providerAccessState(install, null))
    }

    @Test fun aSessionFromOlderPackageConnectionSettingsIsNotReportedConnected() {
        val original = buildJsonObject { put("namespace", "service"); put("appVersion", "1") }
        val updated = JsonObject(original + ("appVersion" to JsonPrimitive("2")))
        val signed = install.copy(manifest = install.manifest.copy(signedSession = updated))
        val auth = buildJsonObject { putJsonObject("signedSession") {
            put("scope", ProviderSignedSession.scopeFor(original))
            put("session_id", "retained-id"); put("session_secret", "retained-secret")
            put("expires_at", "2026-11-02T00:00:00Z")
        } }
        assertEquals(ProviderAccessState.CONFIGURATION_CHANGED, providerAccessState(signed, auth, 1))
        assertEquals("retained-id", auth.getValue("signedSession").jsonObject["session_id"]?.jsonPrimitive?.content,
            "Reading connection status must not erase an old session")
    }
}
