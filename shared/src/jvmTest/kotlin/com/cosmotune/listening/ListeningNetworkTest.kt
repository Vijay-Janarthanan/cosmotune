package com.cosmotune.listening

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class ListeningNetworkTest {
    @Test fun mediaCheckUsesABoundedRangeAndReportsSuccess() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("audio"))
            server.start()
            val result = ListeningNetwork.timed("Media") { ListeningNetwork.readMedia(server.url("/audio?token=secret").toString(), mapOf("X-Test" to "test")) }
            assertTrue(result.ok)
            val request = server.takeRequest()
            assertEquals("bytes=0-1023", request.getHeader("Range"))
            assertEquals("test", request.getHeader("X-Test"))
            assertFalse(ListeningDiagnostics(0, listOf(result)).report().contains("secret"))
        }
    }

    @Test fun providerRejectionsAreExplainedWithoutLeakingSignedUrls() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("private details"))
            server.start()
            val result = ListeningNetwork.timed("Media") { ListeningNetwork.readMedia(server.url("/audio?key=secret").toString(), emptyMap()) }
            assertFalse(result.ok)
            assertTrue(result.detail.contains("rejected"))
            val report = ListeningDiagnostics(0, listOf(result)).report()
            assertFalse(report.contains("secret")); assertFalse(report.contains("private details")); assertFalse(report.contains("http://"))
        }
    }
}
