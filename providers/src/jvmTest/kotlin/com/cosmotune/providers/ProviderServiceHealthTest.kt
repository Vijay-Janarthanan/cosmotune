package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import kotlin.test.*

class ProviderServiceHealthTest {
    private fun manifest(checks: List<ProviderHealthCheck>, domains: List<String> = listOf("localhost")) =
        ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"), permissions = ProviderPermissions(network = domains), serviceHealth = checks)

    @Test fun healthDeclarationsCannotExpandNetworkPermissionsOrSendMutationRequests() {
        assertFailsWith<IllegalArgumentException> { manifest(listOf(ProviderHealthCheck("a", "https://other.example/status"))).validate() }
        assertFailsWith<IllegalArgumentException> { manifest(listOf(ProviderHealthCheck("a", "https://localhost/status", method = "POST"))).validate() }
        assertFailsWith<IllegalArgumentException> { manifest(listOf(ProviderHealthCheck("a", "http://localhost/status"))).validate() }
        assertFailsWith<IllegalArgumentException> { manifest(listOf(ProviderHealthCheck("a", "https://user:secret@localhost/status"))).validate() }
        assertEquals(1, manifest(listOf(ProviderHealthCheck("a", "https://localhost/status"))).validate().serviceHealth.size)
    }

    @Test fun explicitChecksUseNoSavedAuthenticationAndCacheOnlyTheReportedStatus(): Unit = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("""{"services":{"audio":{"status":"degraded"}}}"""))
            val installed = ProviderInstall(manifest(listOf(ProviderHealthCheck("status", server.url("/status").toString(), serviceKey = "audio"))),
                "a".repeat(64), allowLocalNetwork = true)
            ProviderServiceHealth(client, now = { 1000L }).use { health ->
                val first = health.check(installed)
                assertEquals(ProviderServiceState.DEGRADED, first.checks.single().state)
                assertEquals(200, first.checks.single().httpStatus)
                val request = server.takeRequest()
                assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("Authorization"))
                assertEquals("GET", request.method)
                assertEquals(first, health.check(installed))
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(503))
                assertEquals(ProviderServiceState.UNKNOWN, health.check(installed, force = true).checks.single().state)
                assertTrue(installed.enabled)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun endpointReachabilityAndServiceStatusAreNotConflated() {
        assertEquals(ProviderServiceState.REACHABLE, ProviderServiceHealth.classify(200, "<html>Site</html>", null))
        assertEquals(ProviderServiceState.UNKNOWN, ProviderServiceHealth.classify(200, """{"status":"ok"}""", "missing"))
        assertEquals(ProviderServiceState.UNKNOWN, ProviderServiceHealth.classify(200, """{"status":503,"ok":true}""", null))
        assertEquals(ProviderServiceState.UNAVAILABLE, ProviderServiceHealth.classify(200, """{"ok":false}""", null))
        assertEquals(ProviderServiceState.HEALTHY, ProviderServiceHealth.classify(200, """{"status":"healthy"}""", null))
        assertEquals(ProviderServiceState.DEGRADED, ProviderServiceHealth.classify(403, "", null))
    }

    @Test fun disabledOrClosedProvidersDoNotRunNetworkChecks(): Unit = runBlocking {
        val health = ProviderServiceHealth(OkHttpClient())
        val installed = ProviderInstall(manifest(emptyList()), "a".repeat(64), enabled = false)
        assertFailsWith<IllegalStateException> { health.check(installed) }
        health.close()
        assertFailsWith<IllegalStateException> { health.check(installed.copy(enabled = true)) }
    }
}
