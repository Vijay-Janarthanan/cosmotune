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

    private fun packageBytes(name: String = "fixture", version: String = "1.0.0"): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to """{"name":"$name","version":"$version","type":["metadata_provider"]}""",
                "index.js" to "registerExtension({});").forEach { (entry, value) ->
                zip.putNextEntry(java.util.zip.ZipEntry(entry)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    @Test fun downloadedPackagesMustMatchTheListedHashIdentityAndVersion() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(false).build()
            val staging = temporary.newFolder()
            val repositories = ProviderRepositories(ProviderPackages(staging), http)
            val bytes = packageBytes()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            fun entry(sha: String = hash, version: String = "1.0.0", id: String = "fixture") =
                ProviderRepositoryEntry(id = id, version = version, downloadUrl = server.url("/fixture.sflx").toString(), sha256 = sha)
            fun serve() = server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

            serve(); val review = repositories.download(entry())
            assertEquals(hash, review.sha256); assertEquals("fixture", review.manifest.name)
            review.file.delete()
            serve(); assertEquals("Provider package does not match its repository hash",
                assertFailsWith<IllegalArgumentException> { repositories.download(entry(sha = "b".repeat(64))) }.message)
            serve(); assertFailsWith<IllegalArgumentException> { repositories.download(entry(version = "2.0.0")) }
            serve(); assertFailsWith<IllegalArgumentException> { repositories.download(entry(id = "other")) }
            // A rejected download leaves nothing staged for review.
            assertTrue(staging.listFiles().orEmpty().none { it.name.startsWith("review-") })
            // A redirect may not downgrade the package transfer to plain HTTP.
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost/fixture.sflx"))
            assertFailsWith<IllegalArgumentException> { repositories.download(entry()) }
        }
    }

    @Test fun listingsOnlyOfferRealUpdates() {
        val manifest = ProviderManifest(name = "fixture", version = "1.2.0", type = listOf("metadata_provider"))
        val installed = ProviderInstall(manifest, "a".repeat(64))
        fun listed(version: String, sha: String = "a".repeat(64)) =
            ProviderRepositoryEntry(id = "fixture", version = version, downloadUrl = "https://example.invalid/p.sflx", sha256 = sha)
        assertEquals(ProviderListingStatus.NEW, listed("1.0.0").statusAgainst(null))
        assertEquals(ProviderListingStatus.UPDATE, listed("1.10.0").statusAgainst(installed))
        assertEquals(ProviderListingStatus.CURRENT, listed("1.2.0").statusAgainst(installed))
        assertEquals(ProviderListingStatus.UPDATE, listed("1.2.0", "b".repeat(64)).statusAgainst(installed))
        assertEquals(ProviderListingStatus.OLDER, listed("1.1.9").statusAgainst(installed))
    }
}
