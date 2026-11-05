package com.cosmotune.music.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class DesktopAddonSettingsTest {
    @Test fun providerDownloadsUseTheirOwnQualityInsteadOfTheStoredStreamingCeiling() {
        assertEquals(128 to false, DesktopSourceRegistry.providerRequest("STANDARD", forDownload = true))
        assertEquals(null to false, DesktopSourceRegistry.providerRequest("HIGH", forDownload = true))
        assertEquals(null to true, DesktopSourceRegistry.providerRequest("LOSSLESS", forDownload = true))
    }

    @Test fun catalogueCachesRefreshAndOnlyAdvertisedResourcesAreContacted() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        var path = ""
        server.createContext("/manifest.json") { request ->
            val bytes = """{"id":"fixture","resources":["search","album"]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.createContext("/album/") { request ->
            reads.incrementAndGet(); path = request.requestURI.rawPath
            val bytes = """{"id":"catalogue/one","title":"An album","tracks":[]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.start()
        val client = DesktopAddonClient("http://127.0.0.1:${server.address.port}")
        try {
            assertTrue(client.album("catalogue/one").isSuccess)
            assertTrue(client.album("catalogue/one").isSuccess)
            assertEquals(1, reads.get())
            assertEquals("/album/catalogue%2Fone", path)
            assertTrue(client.album("catalogue/one", force = true).isSuccess)
            assertEquals(2, reads.get())
            assertTrue(client.artist("unadvertised").isFailure)
            assertEquals(2, reads.get())
        } finally { client.clear(); server.stop(0) }
    }

    @Test fun savedValuesReachDeclaredParametersAndTheConnectionQualityStillWins() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var parameters = emptyMap<String, String>()
        server.createContext("/manifest.json") { request ->
            val bytes = """{"id":"fixture","resources":["search","stream"],"settings":[{"key":"language","label":"Language","default":"english"},{"key":"token","type":"password","secret":true},{"key":"quality","default":"lossless"}]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.createContext("/search") { request ->
            parameters = request.requestURI.rawQuery.split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('='), "UTF-8")
            }
            val bytes = """{"tracks":[]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.start()
        val client = DesktopAddonClient("http://127.0.0.1:${server.address.port}", mapOf("language" to "tamil", "token" to "fixture-secret", "quality" to "lossless", "undeclared" to "ignored"))
        try {
            assertTrue(client.search("A recording", "high").isSuccess)
            assertEquals("tamil", parameters["language"])
            assertEquals("fixture-secret", parameters["token"])
            assertEquals("high", parameters["quality"])
            assertNull(parameters["undeclared"])
            assertEquals("A recording", parameters["q"])
        } finally { client.clear(); server.stop(0) }
    }

    @Test fun anExplicitRefreshRereadsTheManifestSoNewlyDeclaredPagesOpen() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val manifestReads = java.util.concurrent.atomic.AtomicInteger()
        val artistReads = java.util.concurrent.atomic.AtomicInteger()
        val resources = java.util.concurrent.atomic.AtomicReference("\"search\",\"album\"")
        server.createContext("/manifest.json") { request ->
            manifestReads.incrementAndGet()
            val bytes = """{"id":"fixture","resources":[${resources.get()}]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.createContext("/artist/") { request ->
            artistReads.incrementAndGet()
            val bytes = """{"id":"one","name":"An artist","tracks":[]}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }; request.close()
        }
        server.start()
        val client = DesktopAddonClient("http://127.0.0.1:${server.address.port}")
        try {
            assertTrue(client.artist("one").isFailure)
            resources.set("\"search\",\"album\",\"artist\"")
            // Ordinary reads keep the cached description: no surprise requests while browsing.
            assertTrue(client.artist("one").isFailure)
            assertEquals(1, manifestReads.get())
            assertEquals(0, artistReads.get())
            assertTrue(client.artist("one", force = true).isSuccess)
            assertEquals(2, manifestReads.get())
            assertEquals(1, artistReads.get())
            // And a source that withdraws a page type stops being asked for it after a refresh.
            resources.set("\"search\"")
            assertTrue(client.artist("one", force = true).isFailure)
            assertEquals(1, artistReads.get())
        } finally { client.clear(); server.stop(0) }
    }
}
