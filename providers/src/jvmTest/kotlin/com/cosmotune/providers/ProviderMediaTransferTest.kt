package com.cosmotune.providers

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderMediaTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun transport(server: MockWebServer): ProviderNetwork {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(tls.sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        return ProviderNetwork(ProviderPermissions(listOf("localhost")), true, client)
    }
    private fun checkpoint(target: File, url: String, prefix: String, validator: String = "\"edition-one\"") {
        File(target.parentFile, target.name + ".part").writeText(prefix)
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        File(target.parentFile, target.name + ".checkpoint").writeText(buildJsonObject { put("urlHash", hash); put("validator", validator) }.toString())
    }

    @Test fun resumeRequiresTheSameValidatorAndAnExactContiguousRange() {
        MockWebServer().use { server ->
            val network = transport(server)
            val target = File(temporary.newFolder(), "recording.m4a")
            val url = server.url("/audio?private-signed-token").toString()
            checkpoint(target, url, "abc")
            server.enqueue(MockResponse().setResponseCode(206).addHeader("ETag", "\"edition-one\"")
                .addHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            ProviderMediaTransfer(network, 100).download(url, target)
            assertEquals("abcdef", target.readText())
            val request = server.takeRequest()
            assertEquals("bytes=3-", request.getHeader("Range"))
            assertEquals("\"edition-one\"", request.getHeader("If-Range"))
            assertFalse(File(target.parentFile, target.name + ".checkpoint").exists())
        }
    }

    @Test fun ignoredRangeRestartsWithoutAppendingAnotherRecording() {
        MockWebServer().use { server ->
            val network = transport(server)
            val target = File(temporary.newFolder(), "song.flac")
            val url = server.url("/song").toString()
            checkpoint(target, url, "old")
            server.enqueue(MockResponse().addHeader("ETag", "\"new\"").setBody("new recording"))
            ProviderMediaTransfer(network, 100).download(url, target)
            assertEquals("new recording", target.readText())
        }
    }

    @Test fun mismatchedRangeOrValidatorNeverReplacesAnExistingCompleteFile() {
        MockWebServer().use { server ->
            val network = transport(server)
            val target = File(temporary.newFolder(), "song.flac").apply { writeText("complete original") }
            val url = server.url("/song").toString()
            for ((range, validator) in listOf("bytes 2-5/6" to "\"edition-one\"", "bytes 3-5/6" to "\"other\"", "bytes 3-4/6" to "\"edition-one\"")) {
                checkpoint(target, url, "abc")
                server.enqueue(MockResponse().setResponseCode(206).addHeader("ETag", validator).addHeader("Content-Range", range).setBody("def"))
                assertFailsWith<IllegalArgumentException> { ProviderMediaTransfer(network, 100).download(url, target) }
                assertEquals("complete original", target.readText())
                assertFalse(File(target.parentFile, target.name + ".part").exists())
            }
        }
    }

    @Test fun chunkedOversizeAndCancellationCannotPublishAnIncompleteDownload() {
        MockWebServer().use { server ->
            val network = transport(server)
            val target = File(temporary.newFolder(), "song.m4a")
            server.enqueue(MockResponse().setChunkedBody("123456789", 2))
            assertFailsWith<IllegalArgumentException> { ProviderMediaTransfer(network, 8).download(server.url("/").toString(), target) }
            assertFalse(target.exists())
            server.enqueue(MockResponse().addHeader("ETag", "\"one\"").setBody("data"))
            assertFailsWith<IllegalStateException> { ProviderMediaTransfer(network, 8).download(server.url("/cancel").toString(), target) { _, _ -> network.cancel() } }
            assertFalse(target.exists())
        }
    }
}
