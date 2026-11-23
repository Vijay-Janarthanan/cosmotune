package com.cosmotune.podio

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** A private feed's login goes with its episodes too, as they play or download, and only to that feed's server. */
class PodioFeedLoginEpisodesTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest fun reset() { servers.forEach { it.shutdown() }; PodioFeedLogins.known = emptyMap() }

    private fun server() = MockWebServer().also { it.start(); servers += it }

    @Test fun theAppsRequestsToThePrivateServerCarryTheLoginAndNoOtherServerSeesIt() {
        val private = server()
        val other = server()
        val feed = private.url("/feed.xml").toString()
        PodioFeedLogins.known = mapOf(feed to PodioFeedLogin("ann", "secret"))
        // The episode moves to a CDN on another server: the login stays behind.
        private.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/cdn/1.mp3").toString()))
        other.enqueue(MockResponse().setBody("audio"))
        val client = OkHttpClient.Builder().addInterceptor(PodioFeedLogins.interceptor).build()
        client.newCall(Request.Builder().url(private.url("/episodes/1.mp3")).build()).execute().use { assertEquals("audio", it.body!!.string()) }
        assertEquals("Basic YW5uOnNlY3JldA==", private.takeRequest().getHeader("Authorization"))
        assertNull(other.takeRequest().getHeader("Authorization"))
    }

    @Test fun theDesktopReaderIsGivenTheLoginForThatServerOnly() {
        PodioFeedLogins.known = mapOf("https://private.example/feed.xml" to PodioFeedLogin("ann", "secret"))
        assertEquals(mapOf("User-Agent" to "x", "Authorization" to "Basic YW5uOnNlY3JldA=="),
            PodioFeedLogins.headersFor("https://private.example/media/1.mp3", mapOf("User-Agent" to "x")))
        assertEquals(emptyMap(), PodioFeedLogins.headersFor("https://cdn.example/1.mp3"))
        assertEquals(mapOf("Authorization" to "Bearer own"), PodioFeedLogins.headersFor("https://private.example/1.mp3", mapOf("Authorization" to "Bearer own")))
    }

    @Test fun aPlayerThatStartsBeforeAnyPodioScreenReadsTheLoginsFromTheLibrary() {
        val file = Files.createTempDirectory("podio-logins").resolve("library.json").toFile()
        PodioLibrary(file).update { it.copy(feedLogins = mapOf("https://vault.example/feed" to PodioFeedLogin("bo", "pw"))) }
        PodioFeedLogins.known = emptyMap()
        PodioFeedLogins.install(file)
        assertEquals(PodioFeedLogin("bo", "pw"), PodioFeedLogins.forUrl("https://vault.example/ep.mp3"))
    }
}
