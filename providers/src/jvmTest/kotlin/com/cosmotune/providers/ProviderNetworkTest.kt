package com.cosmotune.providers

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import kotlin.test.*

class ProviderNetworkTest {
    private val permissions = ProviderPermissions(network = listOf("localhost", "example.com", "*.example.org", "127.0.0.1"))
    private fun tls(): Pair<HandshakeCertificates, OkHttpClient> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return server to OkHttpClient.Builder().sslSocketFactory(client.sslSocketFactory(), client.trustManager).build()
    }

    @Test fun undeclaredDomainsAndPrivateLiteralOrReboundAddressesAreRejected() {
        val network = ProviderNetwork(permissions)
        assertFailsWith<IllegalArgumentException> { network.address("https://unlisted.example/") }
        assertFailsWith<IllegalArgumentException> { network.address("https://example.org/") }
        assertEquals("a.example.org", network.address("https://a.example.org/").host)
        assertFailsWith<UnknownHostException> { network.address("https://127.0.0.1/") }
        assertFailsWith<UnknownHostException> { ProviderNetwork.validateAddresses(listOf(InetAddress.getByName("10.0.0.1")), false) }
        assertFailsWith<UnknownHostException> { ProviderNetwork.validateAddresses(listOf(InetAddress.getByName("100.64.0.1")), false) }
        assertFailsWith<IllegalArgumentException> { network.address("https://user:password@example.com/") }
        assertFailsWith<IllegalArgumentException> { network.address("http://example.com/") }
    }

    @Test fun redirectsCannotForwardSourceHeadersToAnotherOriginAndCookiesStayPerProvider() {
        val (tls, client) = tls()
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.useHttps(tls.sslSocketFactory(), false); second.useHttps(tls.sslSocketFactory(), false)
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/final")))
            second.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "source=value; Path=/; Secure"))
            val network = ProviderNetwork(permissions, true, client)
            val options = buildJsonObject { putJsonObject("headers") { put("X-Account-Credential", "private"); put("Cookie", "manual=private") } }
            assertTrue(network.request(first.url("/").toString(), options).getValue("ok").jsonPrimitive.boolean)
            assertEquals("private", first.takeRequest().getHeader("X-Account-Credential"))
            val redirected = second.takeRequest()
            assertNull(redirected.getHeader("X-Account-Credential"))
            assertNull(redirected.getHeader("Cookie"))
            second.enqueue(MockResponse().setBody("own"))
            network.request(second.url("/again").toString(), JsonObject(emptyMap()))
            assertEquals("source=value", second.takeRequest().getHeader("Cookie"))
            second.enqueue(MockResponse().setBody("other"))
            ProviderNetwork(permissions, true, client).request(second.url("/other-provider").toString(), JsonObject(emptyMap()))
            assertNull(second.takeRequest().getHeader("Cookie"))
        } }
    }

    @Test fun chunkedMetadataIsBoundedAndCancellationPreventsFutureRequests() {
        val (tls, client) = tls()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(ProviderNetwork.MAX_METADATA_BYTES + 1), 4096))
            val network = ProviderNetwork(permissions, true, client)
            assertFailsWith<IllegalArgumentException> { network.request(server.url("/").toString(), JsonObject(emptyMap())) }
            network.cancel()
            assertFailsWith<IllegalStateException> { network.request(server.url("/").toString(), JsonObject(emptyMap())) }
            assertEquals(1, server.requestCount)
        }
    }
}
