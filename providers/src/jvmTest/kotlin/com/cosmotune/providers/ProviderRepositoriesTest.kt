package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderRepositoriesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repositoryRequiresHttpsValidHashesAndUniqueProviderIdentities() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(false).build()
            val repositories = ProviderRepositories(ProviderPackages(temporary.newFolder()), http)
            val entry = """{"id":"fixture","version":"1.0.0","download_url":"${server.url("/fixture.sflx")}","sha256":"${"a".repeat(64)}"}"""
            server.enqueue(MockResponse().setBody("""{"version":1,"extensions":[$entry]}"""))
            assertEquals("fixture", repositories.fetch(server.url("/registry.json").toString()).extensions.single().identity)
            server.enqueue(MockResponse().setBody("""{"version":1,"extensions":[$entry,$entry]}"""))
            assertFailsWith<IllegalArgumentException> { repositories.fetch(server.url("/registry.json").toString()) }
            assertFailsWith<IllegalArgumentException> { repositories.fetch("http://localhost/registry.json") }
        }
    }
}
