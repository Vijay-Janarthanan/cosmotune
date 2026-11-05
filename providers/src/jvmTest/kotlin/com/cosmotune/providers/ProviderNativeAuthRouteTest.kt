package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderNativeAuthRouteTest {
    @get:Rule val temporary = TemporaryFolder()
    private val route = ProviderNativeAuthRoute("com.cosmotune.app", "cosmotune")
    private val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"),
        permissions = ProviderPermissions(listOf("localhost"), storage = true)), "0".repeat(64))

    @Test fun admitsOnlyOwnedHierarchicalProviderCallbacks() {
        assertTrue(route.allows("com.cosmotune.app://provider-auth/fixture?code=x&state=y"))
        assertTrue(route.allows("COSMOTUNE://PROVIDER-AUTH/fixture#code=x&state=y"))
        listOf("cosmotune-tv://provider-auth/fixture", "mornye://provider-auth/fixture",
            "https://provider-auth/fixture", "cosmotune://party/fixture", "cosmotune:provider-auth",
            "cosmotune://user@provider-auth/fixture", "cosmotune://provider-auth:123/fixture",
            "cosmotune://provider-auth.evil/fixture", "cosmotune://provider-auth/" + "x".repeat(16_384),
            "cosmotune://provider-auth/invalid space").forEach { assertFalse(route.allows(it), it.take(100)) }
        assertFailsWith<IllegalArgumentException> { ProviderNativeAuthRoute("https") }
        assertFailsWith<IllegalArgumentException> { ProviderNativeAuthRoute("bad_scheme") }
        val tv = ProviderNativeAuthRoute("com.cosmotune.tv", "cosmotune-tv")
        assertTrue(tv.allows("cosmotune-tv://provider-auth/fixture"))
        assertFalse(tv.allows("cosmotune://provider-auth/fixture"))
    }

    private fun start(store: ProviderStore, selected: ProviderInstall = install, pkce: Boolean = true): String {
        val auth = ProviderAuth(selected, store, ProviderNetwork(selected.manifest.permissions))
        val response = auth.call(if (pkce) "startOAuthWithPKCE" else "openAuthUrl", buildJsonArray { addJsonObject {
            put("authUrl", "https://localhost/authorize"); put("clientId", "fixture-client")
            put("redirectUri", "cosmotune://provider-auth/fixture")
        } }).jsonObject
        return "cosmotune://provider-auth/fixture?code=fixture-code&state=" +
            response.getValue("authUrl").jsonPrimitive.content.toHttpUrl().queryParameter("state")
    }

    @Test fun routesOnePendingPkceProofAndRejectsWrongAddressAndReplay() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        store.update { it.copy(installed = listOf(install)) }
        ProviderManager(store).use { manager ->
            val callback = start(store)
            assertNull(manager.completeRoutedAuthentication(callback), "Desktop has no implicit OS registration")
            manager.configureNativeAuthentication(route::allows)
            assertTrue(manager.prepareBrowserAuthentication("fixture").automaticCallback)
            val before = store.state.value.auth
            assertNull(manager.completeRoutedAuthentication(callback.replace("/fixture?", "/another?")))
            assertNull(manager.completeRoutedAuthentication(callback + "&state=duplicate"))
            assertNull(manager.completeRoutedAuthentication(callback.replace("state=", "state=wrong")))
            assertEquals(before, store.state.value.auth)
            assertEquals("fixture", manager.completeRoutedAuthentication(callback))
            assertEquals(JsonPrimitive("fixture-code"), store.state.value.auth["fixture"]?.get("code"))
            assertNull(manager.completeRoutedAuthentication(callback))
            manager.clearAuthentication("fixture")
            assertNull(manager.completeRoutedAuthentication(callback))
        }
    }

    @Test fun refusesAmbiguousProofsInsteadOfSelectingTheFirstInstalledProvider() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val second = install.copy(manifest = install.manifest.copy(name = "second"))
        store.update { it.copy(installed = listOf(install, second)) }
        val callback = start(store)
        store.update { it.copy(auth = it.auth + ("second" to it.auth.getValue("fixture"))) }
        ProviderManager(store).use { manager ->
            manager.configureNativeAuthentication(route::allows)
            assertNull(manager.completeRoutedAuthentication(callback))
            assertTrue(store.state.value.auth.values.none { it.containsKey("code") })
        }
    }

    @Test fun disabledAndNonPkceSignInsCannotAcceptRoutedCodes() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        store.update { it.copy(installed = listOf(install)) }
        ProviderManager(store).use { manager ->
            manager.configureNativeAuthentication(route::allows)
            assertNull(manager.completeRoutedAuthentication(start(store, pkce = false)))
            assertFalse(manager.prepareBrowserAuthentication("fixture").automaticCallback)
            val callback = start(store)
            manager.setEnabled("fixture", false)
            assertNull(manager.completeRoutedAuthentication(callback))
            assertTrue(store.state.value.auth.values.none { it.containsKey("code") })
            manager.setEnabled("fixture", true)
            manager.close()
            assertNull(manager.completeRoutedAuthentication(callback))
        }
    }
}
