package com.cosmotune.providers

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class CatalogueArtworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(13, 7, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray()
    }
    private fun tls(server: MockWebServer): OkHttpClient {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    @Test fun oneCheckedDownloadServesConcurrentReadersAndSurvivesOfflineReopen() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = tls(server)
            val bytes = png()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).addHeader("Set-Cookie", "account=private; Secure; Path=/"))
            var policy = ArtworkAccess(true, listOf("localhost"), true)
            val root = temporary.newFolder()
            val cache = CatalogueArtwork(root, { policy }, UnsealedProviderFiles, client)
            val url = server.url("/cover.png?signed-secret-token").toString()
            val reference = requireNotNull(cache.reference("provider:fixture", url))
            assertFalse(reference.contains("signed-secret-token"))
            val readers = List(8) { async { cache.resolve(reference).readBytes() } }.awaitAll()
            readers.forEach { assertContentEquals(bytes, it) }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            val image = requireNotNull(ImageIO.read(cache.resolve(reference)))
            assertEquals(13, image.width); assertEquals(7, image.height)
            // Persisted references contain addresses only inside the protected cache index.
            cache.close()
            withTimeout(5_000) { while (!File(root, "references.bin").isFile) delay(20) }
            policy = policy.copy(download = false)
            CatalogueArtwork(root, { policy }, UnsealedProviderFiles, client).use { reopened ->
                assertContentEquals(bytes, reopened.resolve(reference).readBytes())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun artworkPermissionAndPrivateAddressBoundaryAreEnforcedBeforeConnecting() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = tls(server)
            var policy = ArtworkAccess(false, listOf("localhost"), true)
            CatalogueArtwork(temporary.newFolder(), { policy }, UnsealedProviderFiles, client).use { cache ->
                val reference = requireNotNull(cache.reference("provider:fixture", server.url("/cover").toString()))
                assertFailsWith<IllegalStateException> { cache.resolve(reference) }
                policy = ArtworkAccess(true, emptyList(), true)
                assertFailsWith<IllegalStateException> { cache.resolve(reference) }
                assertEquals(0, server.requestCount)
                assertNull(cache.reference("provider:fixture", "https://user:password@example.com/cover"))
                assertNull(cache.reference("provider:../fixture", "https://example.com/cover"))
                assertFailsWith<IllegalStateException> { cache.resolve("cosmotune-artwork://provider/fixture/../../private") }
            }
        }
    }

    @Test fun chunkedBudgetAndBadImagesNeverReplaceACachedCover() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = tls(server)
            val bytes = png()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            CatalogueArtwork(temporary.newFolder(), { ArtworkAccess(true, listOf("localhost"), true) }, UnsealedProviderFiles, client).use { cache ->
                val reference = requireNotNull(cache.reference("provider:fixture", server.url("/cover").toString()))
                val saved = cache.resolve(reference)
                saved.setLastModified(1)
                server.enqueue(MockResponse().setBody("<svg><script>bad()</script></svg>"))
                assertContentEquals(bytes, cache.resolve(reference).readBytes())
                server.enqueue(MockResponse().setChunkedBody("x".repeat(CatalogueArtwork.MAX_IMAGE_BYTES + 1), 16_384))
                assertContentEquals(bytes, cache.resolve(reference).readBytes())
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun headerBoundsRejectHugeAnimatedTruncatedAndInconsistentImages() {
        val bytes = png()
        assertEquals(13 to 7, ArtworkDimensions.read(bytes))
        assertNull(ArtworkDimensions.read(bytes.copyOf(24)))
        val huge = bytes.copyOf().apply { this[16] = 0x7f }
        assertNull(ArtworkDimensions.read(huge))
        val animated = bytes.copyOf().apply { "acTL".toByteArray().copyInto(this, 12) }
        assertNull(ArtworkDimensions.read(animated))
        assertNull(ArtworkDimensions.read("<svg width='1' height='1'/>".toByteArray()))
        val jpeg = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(19, 11, BufferedImage.TYPE_INT_RGB), "jpeg", out); out.toByteArray() }
        assertEquals(19 to 11, ArtworkDimensions.read(jpeg))
        val webp = byteArrayOf(82,73,70,70,22,0,0,0,87,69,66,80,86,80,56,32,10,0,0,0,0,0,0,0x9d.toByte(),1,42,13,0,7,0)
        assertEquals(13 to 7, ArtworkDimensions.read(webp))
        assertNull(ArtworkDimensions.read(webp.copyOf().apply { this[4] = 23 }))
    }

    @Test fun configuredHttpServerAllowsOnlyItsOwnOriginForPlaintextArtwork() {
        val policy = requireNotNull(ArtworkAccess.httpSource("http://127.0.0.1:43210/private/token/manifest.json", true))
        assertEquals("http://127.0.0.1:43210/", policy.localOrigin.toString())
        assertFalse(policy.revision.contains("token"))
        val network = ProviderNetwork(ProviderPermissions(policy.declaredDomains), policy.allowLocalNetwork,
            allowPublicArtwork = true, localArtworkOrigin = policy.localOrigin)
        assertEquals(43210, network.address("http://127.0.0.1:43210/cover").port)
        assertFailsWith<IllegalArgumentException> { network.address("http://127.0.0.1:43211/cover") }
        assertFailsWith<IllegalArgumentException> { network.address("http://example.com/cover") }
        assertFails { network.address("https://192.168.0.2/cover") }
        assertEquals("public.example.com", network.address("https://public.example.com/cover").host)
    }
}
