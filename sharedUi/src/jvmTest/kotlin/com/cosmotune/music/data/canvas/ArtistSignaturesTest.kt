package com.cosmotune.music.data.canvas

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSignaturesTest {
    @Test fun requiresAnUsableHttpBaseWithoutQueryOrCredentials() {
        assertTrue(ArtistSignatureRepository.isValidBaseUrl("https://example.org/signatures/"))
        for (value in listOf("https://", "https://?token=secret", "file:///tmp/signature", "https://user:password@example.org", "https://example.org?key=value", "https://example.org#fragment")) {
            assertFalse(ArtistSignatureRepository.isValidBaseUrl(value), value)
        }
        assertNull(ArtistSignatureRepository.effectiveBaseUrl(false, "https://example.org"))
    }

    @Test fun ambiguousNamesAndNonHttpAssetsDoNotProduceAnImage() {
        assertNull(ArtistSignatureRepository.parse("""{"matches":[{"artist":"Name"}]}"""))
        assertNull(ArtistSignatureRepository.parse("""{"signatures":[{"url":"http-not-a-url"},{"url":"file:///private"}]}"""))
    }

    @Test fun knownLicencePrecedesLargerUnknownRaster() {
        val parsed = ArtistSignatureRepository.parse("""{"signatures":[
            {"url":"https://example.org/large.png","width":2000,"height":1000},
            {"asset":{"url":"https://example.org/ink.svg","format":"svg"},"license":{"status":"known"},"width":600,"height":100}
        ]}""")
        assertEquals("https://example.org/ink.svg", parsed?.imageUrl)
        assertEquals(6f, parsed?.aspect)
    }

    /** A provider on loopback: `/v1/health` answers [healthy], `/v1/signatures` answers [body] and records each query. */
    private class FixtureProvider(var healthy: Int = 200, var status: Int = 200, var body: String = "") : AutoCloseable {
        val queries = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/health") { exchange ->
                queries += "health"
                val bytes = """{"ok":true}""".toByteArray()
                exchange.sendResponseHeaders(healthy, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/api/v1/signatures") { exchange ->
                queries += exchange.requestURI.rawQuery
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}/api"
        override fun close() = server.stop(0)
    }

    @Test fun realHttpLookupIsCachedPerProviderAndClearedOnChange() = runBlocking {
        ArtistSignatureRepository.clear()
        FixtureProvider(body = """{"signatures":[{"asset":{"url":"https://example.org/s.svg","format":"svg"},"width":500,"height":100}]}""").use { provider ->
            val first = ArtistSignatureRepository.signatureFor("Fixture Singer", provider.base + "/")
            assertEquals("https://example.org/s.svg", first?.imageUrl)
            assertEquals("svg", first?.format)
            assertEquals(listOf("artist=Fixture+Singer"), provider.queries)
            // Same provider (trailing slash is not a different one): served from memory.
            assertEquals(first, ArtistSignatureRepository.signatureFor("fixture singer", provider.base))
            assertEquals(first, ArtistSignatureRepository.cached("Fixture Singer", provider.base))
            assertEquals(1, provider.queries.size)
            // A changed address forgets what the old one said.
            ArtistSignatureRepository.clear()
            assertNull(ArtistSignatureRepository.cached("Fixture Singer", provider.base))
            ArtistSignatureRepository.signatureFor("Fixture Singer", provider.base)
            assertEquals(2, provider.queries.size)
        }
    }

    @Test fun ambiguousAndFailedAnswersShowNothingAndOnlyTheAnswerIsKept() = runBlocking {
        ArtistSignatureRepository.clear()
        FixtureProvider(body = """{"matches":[{"artist":"Twin"},{"artist":"Twin"}]}""").use { provider ->
            assertNull(ArtistSignatureRepository.signatureFor("Twin", provider.base))
            assertNull(ArtistSignatureRepository.signatureFor("Twin", provider.base))
            assertEquals(1, provider.queries.size)
            provider.status = 500
            assertNull(ArtistSignatureRepository.signatureFor("Down", provider.base))
            provider.status = 200
            provider.body = """{"signatures":[{"url":"https://example.org/down.png"}]}"""
            assertEquals("https://example.org/down.png", ArtistSignatureRepository.signatureFor("Down", provider.base)?.imageUrl)
            assertEquals(3, provider.queries.size)
        }
        // Off or unconfigured: no lookup at all.
        assertNull(ArtistSignatureRepository.signatureFor("Anyone", null))
        assertNull(ArtistSignatureRepository.signatureFor("Anyone", ""))
    }

    @Test fun savingAnAddressNeedsAHealthyProviderAndMalformedOnesAreNeverRequested() = runBlocking {
        FixtureProvider().use { provider ->
            assertEquals(ArtistSignatureRepository.SaveCheck.Ready(provider.base), ArtistSignatureRepository.checkForSave("  ${provider.base}/  "))
            provider.healthy = 503
            assertEquals(ArtistSignatureRepository.SaveCheck.Unreachable, ArtistSignatureRepository.checkForSave(provider.base))
            assertEquals(listOf("health", "health"), provider.queries)
            assertEquals(ArtistSignatureRepository.SaveCheck.Remove, ArtistSignatureRepository.checkForSave("   "))
            assertEquals(ArtistSignatureRepository.SaveCheck.Invalid, ArtistSignatureRepository.checkForSave("${provider.base}?token=secret"))
            assertEquals(ArtistSignatureRepository.SaveCheck.Invalid, ArtistSignatureRepository.checkForSave("ftp://example.org"))
            assertEquals(2, provider.queries.size)
        }
        // Nothing listens here; a refused connection is reported, never thrown.
        assertEquals(ArtistSignatureRepository.SaveCheck.Unreachable, ArtistSignatureRepository.checkForSave("http://127.0.0.1:1"))
    }
}
