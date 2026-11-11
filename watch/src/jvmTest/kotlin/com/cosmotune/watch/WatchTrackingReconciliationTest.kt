package com.cosmotune.watch

import com.cosmotune.watch.model.*
import com.cosmotune.watch.store.*
import com.cosmotune.watch.tracking.WatchTrackingService
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * Trakt and Simkl against loopback fixtures shaped like their documented contracts: token renewal,
 * a 401 part-way, a refused refresh, two-sided watchlist deletions, history that is not sent twice,
 * a scrobble lost to the network, and Simkl's activities gate. No live account is involved, and
 * the public client IDs still have to be registered before any of this reaches a real service.
 */
class WatchTrackingReconciliationTest {
    private fun movie(id: Int, title: String) = """{"title":"$title","year":2000,"ids":{"tmdb":$id,"imdb":"tt$id"}}"""
    private fun entry(id: Int, title: String) = TitleSummary(WatchKey.tmdbFilm(id), TitleType.FILM, title, 2000, ids = ExternalIds(tmdb = id, imdb = "tt$id"))
        .let { MyListEntry(it.key, 1, it) }

    private class Fixture(val server: TestServer, val store: WatchStore, val tracking: WatchTrackingService, val clock: LongArray)

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("watch-tracking").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            TestServer().use { server ->
                val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
                val clock = longArrayOf(1_762_000_000_000L)
                val tracking = WatchTrackingService(OkHttpClient(), store, scope, now = { clock[0] },
                    endpoints = mapOf(WatchTracker.TRAKT to server.url(""), WatchTracker.SIMKL to server.url("")), traktAuth = server.url(""))
                Fixture(server, store, tracking, clock).block()
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    private fun Fixture.traktDeviceFlow(token: String = "token-1", expiresIn: Long = 604_800) {
        server.on("/oauth/device/code") { MockResponse().setBody("""{"device_code":"device","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate","expires_in":60,"interval":1}""") }
        server.on("/oauth/device/token") { MockResponse().setBody("""{"access_token":"$token","refresh_token":"refresh-1","expires_in":$expiresIn}""") }
    }

    private fun Fixture.traktEmptyHistory() {
        server.on("/sync/playback") { MockResponse().setBody("[]") }
        server.on("/sync/watched/movies") { MockResponse().setBody("[]") }
        server.on("/sync/watched/shows") { MockResponse().setBody("[]") }
    }

    private fun Fixture.posts(path: String) = server.requests.filter { it.method == "POST" && it.path == path }
    private fun Fixture.status(service: WatchTracker) = tracking.statuses.value.first { it.service == service }
    private fun Fixture.savedSession(service: WatchTracker) = store.state.value.settings["tracking.default.${service.name}"]
    private fun RecordedRequest.bearer() = getHeader("Authorization")

    @Test fun aTokenNearItsExpiryIsRenewedBeforeTheFirstRequest() = fixture {
        traktDeviceFlow(expiresIn = 30)
        traktEmptyHistory()
        server.on("/oauth/token") { MockResponse().setBody("""{"access_token":"token-2","refresh_token":"refresh-2","expires_in":604800}""") }
        server.on("/sync/watchlist?extended") { MockResponse().setBody("[]") }
        tracking.connect(WatchTracker.TRAKT, "public-client-id")
        tracking.sync(WatchTracker.TRAKT)
        val list = server.requests.first { it.path!!.startsWith("/sync/watchlist?") }
        assertEquals("Bearer token-2", list.bearer())
        assertTrue(posts("/oauth/token").single().body.readUtf8().contains("\"grant_type\":\"refresh_token\""))
        assertTrue(savedSession(WatchTracker.TRAKT)!!.contains("refresh-2"))
    }

    @Test fun a401PartWayRenewsOnceAndFinishesTheSync() = fixture {
        traktDeviceFlow()
        traktEmptyHistory()
        server.on("/oauth/token") { MockResponse().setBody("""{"access_token":"token-2","refresh_token":"refresh-2","expires_in":604800}""") }
        server.on("/sync/watchlist?extended") { request ->
            if (request.bearer() == "Bearer token-2") MockResponse().setBody("[{\"listed_at\":\"2026-11-01T00:00:00Z\",\"movie\":${movie(603, "The Matrix")}}]")
            else MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}""")
        }
        tracking.connect(WatchTracker.TRAKT, "public-client-id")
        tracking.sync(WatchTracker.TRAKT)
        assertEquals(1, posts("/oauth/token").size)
        assertEquals("The Matrix", store.state.value.myList.single().summary.name)
        assertTrue(status(WatchTracker.TRAKT).connected)
        assertTrue(savedSession(WatchTracker.TRAKT)!!.contains("token-2"))
    }

    @Test fun aRefusedRefreshSignsThisViewerOutAndKeepsTheLocalLibrary() = fixture {
        traktDeviceFlow()
        server.on("/oauth/token") { MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }
        server.on("/sync/watchlist?extended") { MockResponse().setResponseCode(401) }
        tracking.connect(WatchTracker.TRAKT, "public-client-id")
        store.update { it.copy(myList = listOf(entry(11, "Kept"))) }
        tracking.sync(WatchTracker.TRAKT)
        assertNull(savedSession(WatchTracker.TRAKT), "the revoked tokens are forgotten")
        assertFalse(status(WatchTracker.TRAKT).connected)
        assertTrue(status(WatchTracker.TRAKT).message!!.contains("sign-in expired"))
        assertEquals("Kept", store.state.value.myList.single().summary.name)
        // Only one refresh attempt: a refused grant is not retried in a loop.
        assertEquals(1, posts("/oauth/token").size)
    }

    @Test fun deletionsOnEitherSideWinAndNeitherIsUndoneByTheOther() = fixture {
        traktDeviceFlow()
        traktEmptyHistory()
        var remote = listOf(1 to "A", 2 to "B")
        server.on("/sync/watchlist?extended") { MockResponse().setBody(remote.joinToString(",", "[", "]") { (id, name) -> "{\"listed_at\":\"2026-11-01T00:00:00Z\",\"movie\":${movie(id, name)}}" }) }
        server.on("/sync/watchlist") { MockResponse().setBody("{}") }
        server.on("/sync/watchlist/remove") { MockResponse().setBody("{}") }
        tracking.connect(WatchTracker.TRAKT, "public-client-id")
        tracking.sync(WatchTracker.TRAKT)
        assertEquals(setOf("A", "B"), store.state.value.myList.map { it.summary.name }.toSet())
        assertTrue(posts("/sync/watchlist").isEmpty() && posts("/sync/watchlist/remove").isEmpty())

        // Here: A removed and C added. There: B removed and D added.
        store.update { s -> s.copy(myList = s.myList.filter { it.summary.name == "B" } + entry(3, "C")) }
        remote = listOf(1 to "A", 4 to "D")
        tracking.sync(WatchTracker.TRAKT)
        assertEquals(setOf("C", "D"), store.state.value.myList.map { it.summary.name }.toSet())
        val added = posts("/sync/watchlist").single().body.readUtf8()
        assertTrue("\"tmdb\":3" in added && "\"tmdb\":2" !in added, added)
        val removed = posts("/sync/watchlist/remove").single().body.readUtf8()
        assertTrue("\"tmdb\":1" in removed, removed)
    }

    @Test fun aWatchLostToTheNetworkIsExportedOnceAtTheNextSyncAndNotResentAfterARemoteDeletion() = fixture {
        traktDeviceFlow()
        traktEmptyHistory()
        server.on("/sync/watchlist?extended") { MockResponse().setBody("[]") }
        server.on("/scrobble/stop") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        server.on("/sync/history") { MockResponse().setBody("""{"added":{"movies":1},"not_found":{"movies":[]}}""") }
        tracking.connect(WatchTracker.TRAKT, "public-client-id")
        val film = entry(5, "Finished offline").summary
        val watched = WatchProgress(film.key, 100 * 60_000, 100 * 60_000, clock[0], film)
        store.update { it.copy(progress = mapOf(film.key.value to watched)) }
        tracking.report(watched, "stop")
        assertNotNull(status(WatchTracker.TRAKT).message, "the failed scrobble is reported, not thrown")
        tracking.sync(WatchTracker.TRAKT)
        val sent = posts("/sync/history").single().body.readUtf8()
        assertTrue("\"tmdb\":5" in sent && "watched_at" in sent, sent)
        // The listener deletes it on Trakt; the next sync must not put it back.
        clock[0] += 60_000
        tracking.sync(WatchTracker.TRAKT)
        assertEquals(1, posts("/sync/history").size)
    }

    @Test fun simklUsesItsDeviceGrantFormsAndSkipsListReadsWhileActivitiesAreUnchanged() = fixture {
        server.on("/oauth2/device") { MockResponse().setBody("""{"device_code":"device","user_code":"ZXCV","verification_url":"https://simkl.com/pin","expires_in":60,"interval":1}""") }
        val forms = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.on("/oauth2/token") { request ->
            val form = request.body.readUtf8().also { forms += it }
            when {
                "grant_type=refresh_token" in form -> MockResponse().setBody("""{"access_token":"simkl-2","refresh_token":"same-refresh","expires_in":7776000}""")
                "device_code=device" in form -> MockResponse().setBody("""{"access_token":"simkl-1","refresh_token":"same-refresh","expires_in":7776000}""")
                else -> MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}""")
            }
        }
        var activity = "2026-11-10T00:00:00Z"
        server.on("/sync/activities") { request ->
            if (request.bearer() == "Bearer simkl-1" && server.requests.count { it.path == "/sync/activities" } == 1) MockResponse().setResponseCode(401)
            else MockResponse().setBody("""{"all":"$activity"}""")
        }
        server.on("/sync/all-items?extended=full") { MockResponse().setBody("""{"movies":[{"status":"plantowatch","added_to_watchlist_at":"2026-11-01T00:00:00Z","movie":${movie(9, "Saved on Simkl")}}]}""") }
        server.on("/sync/all-items?extended=full&episode") { MockResponse().setBody("""{"movies":[]}""") }
        server.on("/sync/playback") { MockResponse().setBody("[]") }
        server.on("/sync/add-to-list") { MockResponse().setBody("{}") }

        tracking.connect(WatchTracker.SIMKL, "public-client-id")
        val poll = server.requests.last { it.path == "/oauth2/token" }
        assertEquals("public-client-id", poll.getHeader("simkl-api-key"))
        assertTrue(forms.last().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"), forms.last())
        assertTrue(forms.last().contains("client_id=public-client-id"))

        store.update { it.copy(myList = listOf(entry(12, "Saved here"))) }
        tracking.sync(WatchTracker.SIMKL)
        assertEquals(setOf("Saved here", "Saved on Simkl"), store.state.value.myList.map { it.summary.name }.toSet())
        assertTrue(savedSession(WatchTracker.SIMKL)!!.contains("simkl-2"), "renewed after the 401 and kept")
        assertTrue(forms.last().contains("grant_type=refresh_token") && forms.last().contains("refresh_token=same-refresh"), forms.last())
        val add = posts("/sync/add-to-list").single().body.readUtf8()
        assertTrue("\"to\":\"plantowatch\"" in add && "\"tmdb\":12" in add, add)

        val listReads = server.requests.count { it.path!!.startsWith("/sync/all-items") }
        tracking.sync(WatchTracker.SIMKL)
        assertEquals(listReads, server.requests.count { it.path!!.startsWith("/sync/all-items") }, "unchanged activities read nothing more")
        activity = "2026-11-11T00:00:00Z"
        tracking.sync(WatchTracker.SIMKL)
        assertTrue(server.requests.count { it.path!!.startsWith("/sync/all-items") } > listReads, "changed activities read again")
    }
}
