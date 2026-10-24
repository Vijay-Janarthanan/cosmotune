package com.cosmotune.music.desktop

import com.cosmotune.music.data.sources.MAX_ADDON_JSON_BYTES
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAddonResponseLimitTest {
    @Test fun chunkedAndDeclaredOversizedProviderResponsesFailWithinTheBudget() = runBlocking {
        for (chunked in listOf(false, true)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val bytes = ByteArray((MAX_ADDON_JSON_BYTES + 1).toInt()) { 32 }
            server.createContext("/manifest.json") { request ->
                request.responseHeaders.add("Content-Type", "application/json")
                request.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
                runCatching { request.responseBody.use { it.write(bytes) } }
                request.close()
            }
            server.start()
            try {
                val result = DesktopAddonClient("http://127.0.0.1:${server.address.port}").manifest()
                assertTrue(result.isFailure)
                assertEquals("Addon response exceeds 2 MB", result.exceptionOrNull()?.message)
            } finally {
                server.stop(0)
            }
        }
    }
}
