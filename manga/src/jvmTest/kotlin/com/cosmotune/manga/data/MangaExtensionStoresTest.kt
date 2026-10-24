package com.cosmotune.manga.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalSerializationApi::class)
class MangaExtensionStoresTest {
    private val fingerprint = "a".repeat(64)
    private fun tls(block: (MockWebServer, MangaExtensionStores) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            block(server, MangaExtensionStores(client))
        }
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }; output.toByteArray()
    }

    @Test fun currentProtobufStoreAndGzipPayloadResolveCatalogueAndKey() = tls { server, stores -> runBlocking {
        val entry = WireExtension("Example", "org.example.manga", WireResources(server.url("/source.apk").toString()), "1.6", 5, "5.0", 1,
            listOf(WireSource(42, "Example", "en")))
        val wire = WireStore("Publisher", signingKey = fingerprint, extensionList = WireList(listOf(entry, entry.copy(versionCode = 6))))
        server.enqueue(MockResponse().setBody(Buffer().write(gzip(ProtoBuf.encodeToByteArray(WireStore.serializer(), wire)))))
        val result = stores.fetch(server.url("/index.pb").toString(), fingerprint)
        assertEquals(fingerprint, result.store.signingKey)
        assertEquals("Publisher", result.store.name)
        assertEquals(6L, result.extensions.single().versionCode)
        assertEquals(setOf("en"), result.extensions.single().languages)
    } }

    @Test fun legacyJsonAddressFollowsItsPublishedModernIndex() = tls { server, stores -> runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""{"index_v2":"${server.url("/index.pb")}","meta":{"name":"Publisher"}}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(ProtoBuf.encodeToByteArray(WireStore.serializer(),
            WireStore("Publisher", signingKey = fingerprint, extensionList = WireList())))))
        val result = stores.fetch(server.url("/index.min.json").toString())
        assertEquals(server.url("/index.pb").toString(), result.store.indexUrl)
        assertEquals("/index.min.json", server.takeRequest().path)
        assertEquals("/repo.json", server.takeRequest().path)
        assertEquals("/index.pb", server.takeRequest().path)
    } }

    @Test fun changedPublisherKeysAndInsecureArtifactUrlsAreRejected() = tls { server, stores -> runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(ProtoBuf.encodeToByteArray(WireStore.serializer(),
            WireStore("Publisher", signingKey = fingerprint, extensionList = WireList())))))
        assertFailsWith<IllegalArgumentException> { stores.fetch(server.url("/index.pb").toString(), "b".repeat(64)) }
        val entry = WireExtension("Example", "org.example.manga", WireResources("http://example.org/source.apk"))
        val insecure = WireStore("Publisher", signingKey = fingerprint, extensionList = WireList(listOf(entry)))
        server.enqueue(MockResponse().setBody(Buffer().write(ProtoBuf.encodeToByteArray(WireStore.serializer(), insecure))))
        assertFailsWith<IllegalArgumentException> { stores.fetch(server.url("/index.pb").toString()) }
        assertFailsWith<IllegalArgumentException> { stores.fetch("http://example.org/index.pb") }
    } }

    @Test fun compressedIndexCannotExceedItsDecodedBudget() = tls { server, stores -> runBlocking<Unit> {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(gzip(ByteArray(8 * 1024 * 1024 + 1))), 4096))
        assertFailsWith<IllegalArgumentException> { stores.fetch(server.url("/index.pb").toString()) }
    } }
}
