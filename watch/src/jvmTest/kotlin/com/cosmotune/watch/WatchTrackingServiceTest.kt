package com.cosmotune.watch

import com.cosmotune.watch.store.*
import com.cosmotune.watch.tracking.WatchTrackingService
import com.cosmotune.watch.model.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse

class WatchTrackingServiceTest {
    @Test fun publicDeviceFlowStoresTokensPrivatelyAndRestoresRemoteWatchlist() = runBlocking {
        val root = Files.createTempDirectory("watch-tracking").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            TestServer().use { server ->
                server.on("/oauth/device/code") { MockResponse().setBody("""{"device_code":"private-device","user_code":"ABCD1234","verification_url":"https://auth.trakt.tv/activate","expires_in":60,"interval":1}""") }
                server.on("/oauth/device/token") { MockResponse().setBody("""{"access_token":"private-token","refresh_token":"private-refresh","expires_in":604800}""") }
                server.on("/sync/watchlist?extended=full&page=1&limit=100") { MockResponse().setBody("""[{"listed_at":"2026-11-01T00:00:00Z","movie":{"title":"The Matrix","year":1999,"ids":{"tmdb":603,"imdb":"tt0133093"}}}]""") }
                server.on("/sync/playback?extended=full") { MockResponse().setBody("[]") }
                server.on("/sync/watched/movies?extended=full") { MockResponse().setBody("[]") }
                server.on("/sync/watched/shows?extended=full") { MockResponse().setBody("[]") }
                val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
                val tracking = WatchTrackingService(OkHttpClient(), store, scope, endpoints = mapOf(WatchTracker.TRAKT to server.url("")), traktAuth = server.url(""))
                tracking.connect(WatchTracker.TRAKT, "public-client-id")
                assertTrue(tracking.statuses.value.first { it.service == WatchTracker.TRAKT }.connected)
                tracking.sync(WatchTracker.TRAKT)
                assertEquals("The Matrix", store.state.value.myList.single().summary.name)
                assertFalse(SyncWatchLibrary.snapshot(store.state.value).toString().contains("private-token"))
                assertFalse(tracking.statuses.value.toString().contains("private-device"))
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
