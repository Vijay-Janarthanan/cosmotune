package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderCookieJarTest {
    @get:Rule val temporary = TemporaryFolder()
    private val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"),
        permissions = ProviderPermissions(listOf("localhost"), storage = true)), "0".repeat(64), allowLocalNetwork = true)
    @Test fun persistentCookiesReopenButSessionCookiesStayOnlyInTheCurrentApp() {
        MockWebServer().use { server ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            fun network(state: ProviderStore, source: ProviderInstall = install) = ProviderNetwork(source.manifest.permissions, true, client,
                cookieJar = ProviderCookieJar.forProvider(source, state))
            server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "persistent=provider-token; Path=/private; Max-Age=600; Secure; HttpOnly")
                .addHeader("Set-Cookie", "session=provider-session; Path=/private; Secure"))
            network(store).request(server.url("/private/start").toString(), JsonObject(emptyMap())); assertNull(server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("same app"))
            network(store).request(server.url("/private/again").toString(), JsonObject(emptyMap()))
            val inMemory = server.takeRequest().getHeader("Cookie").orEmpty()
            assertTrue(inMemory.contains("persistent=provider-token")); assertTrue(inMemory.contains("session=provider-session"))
            val reopened = ProviderStore(store.root, UnsealedProviderFiles)
            server.enqueue(MockResponse().setBody("restarted"))
            network(reopened).request(server.url("/private/reopened").toString(), JsonObject(emptyMap()))
            assertEquals("persistent=provider-token", server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("path isolation"))
            network(reopened).request(server.url("/public").toString(), JsonObject(emptyMap())); assertNull(server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("another package"))
            network(reopened, install.copy(manifest = install.manifest.copy(name = "other"))).request(server.url("/private").toString(), JsonObject(emptyMap()))
            assertNull(server.takeRequest().getHeader("Cookie"))
            ProviderCookieJar.clear(reopened, "fixture")
            assertTrue(ProviderStore(store.root, UnsealedProviderFiles).state.value.cookies["fixture"].orEmpty().isEmpty())
        }
    }
    @Test fun clearingRejectsCookiesFromAResponseThatStartedBeforeDisconnect() {
        val address = "https://example.org/private".toHttpUrl()
        val jar = ProviderCookieJar()
        val old = jar.request(address)
        jar.clear()
        jar.receive(listOf(requireNotNull(Cookie.parse(address, "late=value; Secure; Path=/"))), old.generation)
        assertNull(jar.request(address).header)
        val current = jar.request(address)
        jar.receive(listOf(requireNotNull(Cookie.parse(address, "current=value; Secure; Path=/"))), current.generation)
        assertEquals("current=value", jar.request(address).header)
        assertNull(jar.request("http://example.org/private".toHttpUrl()).header)
    }
    @Test fun storageGrantAndCookieBudgetsApplyBeforePersistingProviderCredentials() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val address = "https://localhost/private".toHttpUrl()
        val noStorage = install.copy(manifest = install.manifest.copy(permissions = install.manifest.permissions.copy(storage = false)))
        val ephemeral = ProviderCookieJar.forProvider(noStorage, store)
        ephemeral.receive(listOf(requireNotNull(Cookie.parse(address, "private=token; Max-Age=600; Secure; Path=/"))), ephemeral.request(address).generation)
        assertTrue(store.state.value.cookies.isEmpty())
        val jar = ProviderCookieJar.forProvider(install, store)
        jar.receive((0 until 600).map { requireNotNull(Cookie.parse(address, "cookie$it=value; Max-Age=600; Secure; Path=/")) }, jar.request(address).generation)
        assertEquals(512, store.state.value.cookies["fixture"]?.size)
        assertFalse(jar.request(address).header.orEmpty().contains("cookie0="))
    }
}
