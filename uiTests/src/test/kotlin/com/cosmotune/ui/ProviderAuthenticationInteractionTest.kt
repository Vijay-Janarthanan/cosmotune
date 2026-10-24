package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.providers.*
import com.cosmotune.ui.providers.ProviderAuthenticationEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderAuthenticationInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val protection = object : ProviderFileProtection {
        override fun seal(bytes: ByteArray) = bytes
        override fun open(bytes: ByteArray) = bytes
    }
    private val installed = ProviderInstall(ProviderManifest("fixture", "1.0.0", "Fixture provider", type = listOf("metadata_provider"),
        permissions = ProviderPermissions(listOf("auth.example"), storage = true)), "0".repeat(64))
    private fun manager(redirect: String = "cosmotune://provider-auth/fixture", proof: String = "fixture-state", pkce: Boolean = false): ProviderManager = runBlocking {
        ProviderManager(ProviderStore(temporary.newFolder(), protection)).also { manager ->
            manager.store.update { it.copy(installed = listOf(installed), auth = mapOf("fixture" to buildJsonObject {
                put("_epoch", "fixture-generation")
                putJsonObject("pending") {
                    put("url", "https://auth.example/authorize?state=$proof")
                    put("callback", redirect); put("pkce", pkce)
                    put("state", proof); put("createdAt", System.currentTimeMillis()); put("completed", false)
                }
            })) }
        }
    }

    @Test fun rejectedCallbackStaysEditableAndSuccessfulCallbackCanBeCancelled() {
        manager().use { manager ->
            var opened: String? = null
            compose.setContent { MaterialTheme { Column { ProviderAuthenticationEntry(manager, installed) { opened = it } } } }
            compose.onNodeWithText("Continue provider sign-in").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Open sign-in page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open sign-in page").performClick()
            compose.waitUntil(10_000) { opened != null }
            assertEquals("https://auth.example/authorize?state=fixture-state", opened)
            val field = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            field.performTextInput("cosmotune://provider-auth/fixture?code=private-code&state=wrong")
            compose.onNodeWithText("Continue", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("The callback was not accepted.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertNull(manager.store.state.value.auth["fixture"]?.get("code"))
            field.performTextReplacement("cosmotune://provider-auth/fixture?code=private-code&state=fixture-state")
            compose.onNodeWithText("Continue", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Authorization code received.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(JsonPrimitive("private-code"), manager.store.state.value.auth["fixture"]?.get("code"))
            compose.onNodeWithText("Cancel provider sign-in").performClick()
            compose.waitUntil(10_000) { manager.store.state.value.auth["fixture"]?.keys == setOf("_epoch") }
        }
    }

    @Test fun nativeDisconnectWorksAfterTheProviderLosesItsStoragePermission() {
        manager().use { manager ->
            val noStorage = installed.copy(manifest = installed.manifest.copy(permissions = ProviderPermissions(listOf("auth.example"))))
            runBlocking { manager.store.update { it.copy(installed = listOf(noStorage), auth = mapOf("fixture" to buildJsonObject {
                put("access_token", "private-provider-token"); put("_epoch", "old-generation")
            })) } }
            compose.setContent { MaterialTheme { Column { ProviderAuthenticationEntry(manager, noStorage) { error("Unexpected browser") } } } }
            compose.onNodeWithText("Disconnect provider account").performClick()
            compose.waitUntil(10_000) { manager.store.state.value.auth["fixture"]?.keys == setOf("_epoch") }
            assertNotEquals(JsonPrimitive("old-generation"), manager.store.state.value.auth["fixture"]?.get("_epoch"))
        }
    }

    @Test fun anOperatorSessionIsRecognizedAndNativeDisconnectClearsItsSecret() {
        manager().use { manager ->
            runBlocking { manager.store.update { it.copy(auth = mapOf("fixture" to buildJsonObject {
                put("_epoch", "signed-generation")
                putJsonObject("signedSession") {
                    put("session_id", "fixture-operator-session"); put("session_secret", "fixture-operator-secret")
                    put("expires_at", java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 600_000).toString())
                }
            })) } }
            compose.setContent { MaterialTheme { Column { ProviderAuthenticationEntry(manager, installed) { error("Unexpected browser") } } } }
            compose.onNodeWithText("fixture-operator-secret", substring = true).assertDoesNotExist()
            runBlocking { manager.store.update { old ->
                val auth = old.auth.getValue("fixture")
                old.copy(auth = old.auth + ("fixture" to JsonObject(auth + ("signedSession" to JsonObject(auth.getValue("signedSession").jsonObject +
                    ("expires_at" to JsonPrimitive("2020-01-01T00:00:00Z")))))))
            } }
            compose.onNodeWithText("Provider session expired.", substring = true).assertExists()
            compose.onNodeWithText("Disconnect provider account").performClick()
            compose.waitUntil(10_000) { manager.store.state.value.auth["fixture"]?.keys == setOf("_epoch") }
            assertNotEquals(JsonPrimitive("signed-generation"), manager.store.state.value.auth["fixture"]?.get("_epoch"))
        }
    }

    @Test fun realLoopbackCallbackCompletesTheNativeDialogWithoutPastingACode() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val redirect = "http://127.0.0.1:$port/callback"
        val proof = "fixture-state-long-enough"
        manager(redirect, proof, pkce = true).use { manager ->
            val opened = java.util.concurrent.atomic.AtomicReference<String?>()
            compose.setContent { MaterialTheme { Column { ProviderAuthenticationEntry(manager, installed) { opened.set(it) } } } }
            compose.onNodeWithText("Continue provider sign-in").performClick()
            compose.onNodeWithText("Open sign-in page").performClick()
            compose.waitUntil(10_000) { opened.get() != null }
            assertEquals("https://auth.example/authorize?state=$proof", opened.get())
            val response = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (java.net.URI("$redirect?code=ui-fixture-code&state=$proof").toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).let { request ->
                    request.connectTimeout = 3_000; request.readTimeout = 5_000
                    try { request.inputStream.use { it.readBytes() }; request.responseCode } finally { request.disconnect() }
                }
            } }
            assertEquals(200, response)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Authorization code received.", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Callback URL").fetchSemanticsNodes().isEmpty() }
            assertEquals(JsonPrimitive("ui-fixture-code"), manager.store.state.value.auth["fixture"]?.get("code"))
        }
    }

    @Test fun aFailedBrowserLaunchReleasesItsListenerAndKeepsManualRecovery() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        manager("http://127.0.0.1:$port/callback", "fixture-state-long-enough", pkce = true).use { manager ->
            val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
            compose.setContent { MaterialTheme { Column { ProviderAuthenticationEntry(manager, installed) {
                attempted.set(true); throw java.io.IOException("No browser in test fixture")
            } } } }
            compose.onNodeWithText("Continue provider sign-in").performClick()
            compose.onNodeWithText("Open sign-in page").performClick()
            compose.waitUntil(10_000) { attempted.get() && compose.onAllNodesWithText("The sign-in page could not be opened.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertFailsWith<java.io.IOException> { java.net.Socket("127.0.0.1", port).close() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertIsEnabled()
            assertEquals(JsonPrimitive(false), manager.store.state.value.auth["fixture"]?.get("pending")?.jsonObject?.get("completed"))
        }
    }
}
