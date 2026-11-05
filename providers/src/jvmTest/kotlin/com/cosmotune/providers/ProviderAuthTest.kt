package com.cosmotune.providers

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderAuthTest {
    @get:Rule val temporary = TemporaryFolder()
    private val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"),
        permissions = ProviderPermissions(listOf("localhost"), storage = true)), "0".repeat(64), allowLocalNetwork = true)
    private fun transport(server: MockWebServer): ProviderNetwork {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return ProviderNetwork(install.manifest.permissions, true, OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    private fun begin(auth: ProviderAuth, url: String): String = auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
        put("authUrl", url); put("clientId", "public-client"); put("redirectUri", "cosmotune://provider-auth/fixture")
        putJsonArray("scopes") { add("library.read"); add("playback") }
    } }).jsonObject.getValue("open_auth_url").jsonPrimitive.content
    private fun callback(url: String, code: String = "a-code") = "cosmotune://provider-auth/fixture?code=$code&state=${url.toHttpUrl().queryParameter("state")}"
    private fun exchange(url: String) = buildJsonArray { addJsonObject {
        put("tokenUrl", url); put("clientId", "public-client"); put("redirectUri", "cosmotune://provider-auth/fixture")
    } }

    @Test fun pkceMatchesTheRfcExampleAndRequestedVerifierLength() {
        // RFC 7636 Appendix B, https://www.rfc-editor.org/rfc/rfc7636#appendix-B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ProviderAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val auth = ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions))
        val proof = auth.call("generatePKCE", buildJsonArray { add(96) }).jsonObject
        assertEquals(96, proof.getValue("verifier").jsonPrimitive.content.length)
        assertEquals(ProviderAuth.challenge(proof.getValue("verifier").jsonPrimitive.content), proof.getValue("challenge").jsonPrimitive.content)
        val started = auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
            put("authUrl", "https://localhost/authorize"); put("clientId", "public-client"); put("redirectUri", "cosmotune://provider-auth/fixture")
        } }).jsonObject
        assertEquals(started["open_auth_url"], started["authUrl"])
        assertEquals(auth.call("getPKCE", JsonArray(emptyList())), started["pkce"])
        val blocked = ProviderAuth(install.copy(manifest = install.manifest.copy(permissions = ProviderPermissions(listOf("localhost")))), store, ProviderNetwork(install.manifest.permissions))
        assertFailsWith<IllegalArgumentException> { blocked.call("getPKCE", JsonArray(emptyList())) }
    }

    @Test fun nativeBrowserCallbackCommitsOnlyThePendingProviderProofAndDisablingClosesIt() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        store.update { it.copy(installed = listOf(install)) }
        val auth = ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions, true))
        ProviderManager(store).use { manager ->
            fun start(): Pair<String, String> {
                val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val callback = "http://127.0.0.1:$port/callback"
                val address = auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
                    put("authUrl", "https://localhost/authorize"); put("clientId", "public-client"); put("redirectUri", callback)
                } }).jsonObject.getValue("open_auth_url").jsonPrimitive.content
                return callback to requireNotNull(address.toHttpUrl().queryParameter("state"))
            }
            val (callback, state) = start()
            val prepared = manager.prepareBrowserAuthentication("fixture")
            assertTrue(prepared.automaticCallback); assertFalse(prepared.toString().contains(state))
            val http = java.net.Proxy.NO_PROXY
            val response = withContext(Dispatchers.IO) {
                (java.net.URL("$callback?code=native-fixture-code&state=$state").openConnection(http) as java.net.HttpURLConnection).let { request ->
                    request.connectTimeout = 3_000; request.readTimeout = 5_000
                    try { request.inputStream.use { it.readBytes() }; request.responseCode } finally { request.disconnect() }
                }
            }
            assertEquals(200, response)
            assertEquals(JsonPrimitive("native-fixture-code"), store.state.value.auth["fixture"]?.get("code"))
            assertEquals(JsonPrimitive(true), store.state.value.auth["fixture"]?.get("pending")?.jsonObject?.get("completed"))
            assertFailsWith<IllegalArgumentException> { manager.prepareBrowserAuthentication("fixture") }
            manager.clearAuthentication("fixture")
            val (second, _) = start()
            assertTrue(manager.prepareBrowserAuthentication("fixture").automaticCallback)
            manager.setEnabled("fixture", false)
            assertFailsWith<java.io.IOException> { java.net.Socket("127.0.0.1", URI(second).port).close() }
        }
    }

    @Test fun httpCallbacksNormalizeTheRootAndHostButNeverAcceptAnotherPortOrMissingAuthority() {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val auth = ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions, true))
        val url = auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
            put("authUrl", "https://localhost/authorize"); put("clientId", "public-client"); put("redirectUri", "http://LOCALHOST:43123")
        } }).jsonObject.getValue("open_auth_url").jsonPrimitive.content
        val state = url.toHttpUrl().queryParameter("state")
        assertFailsWith<IllegalArgumentException> { auth.completeCallback("http://localhost:43124/?code=wrong&state=$state") }
        auth.completeCallback("http://localhost:43123/?code=expected&state=$state")
        assertEquals(JsonPrimitive("expected"), store.state.value.auth["fixture"]?.get("code"))
        val before = store.state.value.auth
        assertFailsWith<IllegalArgumentException> { auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
            put("authUrl", "https://localhost/authorize"); put("clientId", "public-client"); put("redirectUri", "https:/missing-authority")
        } }) }
        assertEquals(before, store.state.value.auth)
    }

    @Test fun browserCallbackAndTokenExchangeStayBoundToThisProviderAndProof() {
        MockWebServer().use { server ->
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            val auth = ProviderAuth(install, store, transport(server))
            val url = begin(auth, server.url("/authorize").toString())
            val request = url.toHttpUrl()
            assertEquals("S256", request.queryParameter("code_challenge_method"))
            assertEquals("library.read playback", request.queryParameter("scope"))
            val verifier = auth.call("getPKCE", JsonArray(emptyList())).jsonObject.getValue("verifier").jsonPrimitive.content
            assertEquals(ProviderAuth.challenge(verifier), request.queryParameter("code_challenge"))
            assertFailsWith<IllegalArgumentException> { auth.completeCallback(callback(url).replace("provider-auth", "wrong-origin")) }
            assertFailsWith<IllegalArgumentException> { auth.completeCallback(callback(url) + "&state=another") }
            auth.completeCallback(callback(url))
            assertFailsWith<IllegalArgumentException> { auth.completeCallback(callback(url)) }
            server.enqueue(MockResponse().setBody("""{"access_token":"provider-only-token","refresh_token":"provider-refresh","expires_in":120}"""))
            val result = auth.call("exchangeCodeWithPKCE", exchange(server.url("/token").toString())).jsonObject
            assertEquals(JsonPrimitive(true), result["success"])
            val sent = server.takeRequest()
            assertEquals("POST", sent.method); assertNull(sent.getHeader("Cookie"))
            val form = URI("http://fixture/?" + sent.body.readUtf8()).toString().toHttpUrl()
            assertEquals(verifier, form.queryParameter("code_verifier")); assertEquals("a-code", form.queryParameter("code"))
            assertEquals(JsonPrimitive(true), auth.call("isAuthenticated", JsonArray(emptyList())))
            val reopened = ProviderAuth(install, ProviderStore(store.root, UnsealedProviderFiles), ProviderNetwork(install.manifest.permissions))
            assertEquals("provider-only-token", reopened.call("getTokens", JsonArray(emptyList())).jsonObject.getValue("access_token").jsonPrimitive.content)
            assertEquals(JsonNull, reopened.call("getAuthCode", JsonArray(emptyList())))
            assertTrue(reopened.call("getPKCE", JsonArray(emptyList())).jsonObject.isEmpty())
            reopened.clear(); assertEquals(JsonPrimitive(false), reopened.call("isAuthenticated", JsonArray(emptyList())))
        }
    }

    @Test fun expiredCallbacksAndStateOverridesAreRefused() {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        var clock = 1_000L
        val auth = ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions, true)) { clock }
        val url = begin(auth, "https://localhost/authorize")
        val original = store.state.value.auth
        clock += 600_001
        assertFailsWith<IllegalArgumentException> { auth.completeCallback(callback(url)) }
        assertFailsWith<IllegalArgumentException> { auth.call("startOAuthWithPKCE", buildJsonArray { addJsonObject {
            put("authUrl", "https://localhost/authorize"); put("clientId", "public-client"); put("redirectUri", "cosmotune://provider-auth/fixture")
            putJsonObject("extraParams") { put("state", "attacker") }
        } }) }
        assertEquals(original, store.state.value.auth, "Invalid requests must preserve the pending proof")
        assertFailsWith<IllegalArgumentException> { auth.call("setAuthCode", buildJsonArray { addJsonObject {
            put("access_token", "token"); put("expires_at", "never")
        } }) }
    }

    @Test fun tokenExtrasCannotReplaceAnOmittedRedirectAndGuestPkceKeepsItsLength() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val auth = ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions, true))
        auth.call("generatePKCE", buildJsonArray { add(64) })
        assertFailsWith<IllegalArgumentException> { auth.call("exchangeCodeWithPKCE", buildJsonArray { addJsonObject {
            put("tokenUrl", "https://localhost/token"); put("clientId", "client"); put("code", "code")
            putJsonObject("extraParams") { put("redirect_uri", "https://unexpected.example/callback") }
        } }) }
        val host = ProviderMetadataHost(install, store)
        createProviderScriptSession("""registerExtension({probe:function(){return auth.generatePKCE(96).verifier.length;}});""",
            buildJsonObject { putJsonObject("permissions") { putJsonArray("network") { add("localhost") }; put("storage", true) }; put("settings", JsonObject(emptyMap())) },
            host::bridge, host.network::cancel).use { runtime ->
            assertEquals(JsonPrimitive(96), Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))))
        }
    }

    @Test fun aTokenResponseCannotReconnectAfterDisconnect() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            val network = transport(server)
            val auth = ProviderAuth(install, store, network)
            val url = begin(auth, server.url("/authorize").toString()); auth.completeCallback(callback(url))
            server.enqueue(MockResponse().setBody("""{"access_token":"late-token","expires_in":120}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            supervisorScope {
                val pending = async(Dispatchers.IO) { auth.call("exchangeCodeWithPKCE", exchange(server.url("/token").toString())) }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                ProviderAuth(install, store, ProviderNetwork(install.manifest.permissions, true)).clear()
                assertFailsWith<IllegalStateException> { pending.await() }
            }
            assertEquals(JsonPrimitive(false), auth.call("isAuthenticated", JsonArray(emptyList())))
        }
    }
}
