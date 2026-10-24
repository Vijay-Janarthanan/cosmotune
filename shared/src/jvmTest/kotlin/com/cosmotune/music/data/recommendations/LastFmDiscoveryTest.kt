package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.*

/** LK-06: Last.fm candidates exist only behind the opt-in, use only public read methods, and send no session key. */
class LastFmDiscoveryTest {
    private val seed = Song("seed", "Billie Jean", "Michael Jackson", null)

    private fun serve(block: suspend (MockWebServer, MutableList<RecordedRequest>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    return when (request.requestUrl?.queryParameter("method")) {
                        "track.getsimilar" -> MockResponse().setBody("""{"similartracks":{"track":[
                            {"name":"Beat It","match":0.9,"duration":258,"artist":{"name":"Michael Jackson"}},
                            {"name":"Smooth Criminal","match":"0.4","artist":{"name":"Michael Jackson"}},
                            {"name":"Billie Jean","match":1,"artist":{"name":"Michael Jackson"}},
                            {"name":"Kiss","match":0.3,"artist":{"name":"Prince"}},
                            {"name":"","match":0.99,"artist":{"name":"Nobody"}}]}}""")
                        "user.gettopartists" -> if (request.path!!.startsWith("/broken")) MockResponse().setResponseCode(500)
                            else MockResponse().setBody("""{"topartists":{"artist":[{"name":"Prince"}]}}""")
                        else -> MockResponse().setResponseCode(400).setBody("""{"error":3,"message":"Invalid Method"}""")
                    }
                }
            }
            server.start()
            runBlocking { block(server, seen) }
        }
    }

    @Test fun nothingIsBuiltWithoutTheOptInAStoredSessionAndAKey() {
        assertNull(LastFmDiscovery.configured(optedIn = false, sessionKey = "s", username = "u", apiKey = "k"))
        assertNull(LastFmDiscovery.configured(optedIn = true, sessionKey = "", username = "u", apiKey = "k"))
        assertNull(LastFmDiscovery.configured(optedIn = true, sessionKey = "s", username = null, apiKey = "k"))
        assertNull(LastFmDiscovery.configured(optedIn = true, sessionKey = "s", username = "u", apiKey = " "))
        assertNull(LastFmDiscovery.configured(true, "s", "u", "k", endpoint = "http://lastfm.example/2.0/"), "plain HTTP is refused")
        assertNotNull(LastFmDiscovery.configured(true, "s", "u", "k"))
    }

    @Test fun similarTracksRankTheAccountsTopArtistsFirstAndNeverRepeatTheSeed() = runBlocking<Unit> {
        serve { server, seen ->
            val discovery = assertNotNull(LastFmDiscovery.configured(true, "session-fixture", "listener", "key-fixture",
                endpoint = "http://127.0.0.1:${server.port}/2.0/", client = OkHttpClient()))
            val candidates = discovery.candidates(seed)
            assertEquals(listOf("Kiss", "Beat It", "Smooth Criminal"), candidates.map { it.title })
            assertEquals(258, candidates[1].durationSeconds)
            assertTrue(candidates.all { it.key.startsWith("lastfm:") })
            // The top artists are cached; a second radio batch only asks for similar tracks.
            discovery.candidates(seed)
            val methods = seen.map { it.requestUrl!!.queryParameter("method") }
            assertEquals(1, methods.count { it == "user.gettopartists" })
            assertEquals(2, methods.count { it == "track.getsimilar" })
            for (request in seen) {
                val url = request.requestUrl!!
                assertEquals("GET", request.method)
                assertNull(url.queryParameter("sk"), "the session key is never sent for discovery")
                assertFalse(request.requestLine.contains("session-fixture"))
                assertEquals("key-fixture", url.queryParameter("api_key"))
            }
            assertEquals("listener", seen.first { it.requestUrl!!.queryParameter("method") == "user.gettopartists" }.requestUrl!!.queryParameter("user"))
        }
    }

    @Test fun failuresYieldNoCandidatesAndRadioIsNotDelayedByTheFeed() = runBlocking<Unit> {
        serve { server, seen ->
            val broken = LastFmDiscovery.configured(true, "s", "listener", "k", endpoint = "http://127.0.0.1:${server.port}/broken/", client = OkHttpClient())!!
            // Top artists failing leaves the similar tracks in Last.fm's own order.
            assertEquals(listOf("Beat It", "Smooth Criminal", "Kiss"), broken.candidates(seed).map { it.title })
            val previous = LocalRecommendations.accountCandidates
            try {
                val asked = java.util.concurrent.atomic.AtomicInteger()
                LocalRecommendations.accountCandidates = { asked.incrementAndGet(); kotlinx.coroutines.delay(50); listOf(
                    RecommendationModel.Candidate("lastfm:x", "Kiss", "Prince", "", 0, 1f, 1)) }
                // The first batch for a seed does not wait for the feed; a later one uses its answer.
                assertTrue(LocalRecommendations.accountFor(seed).isEmpty())
                kotlinx.coroutines.withTimeout(5_000) { while (LocalRecommendations.accountFor(seed).isEmpty()) kotlinx.coroutines.delay(10) }
                assertEquals(listOf("Kiss"), LocalRecommendations.accountFor(seed).map { it.title })
                assertEquals(1, asked.get())
                // Switching the feature off forgets the answers and asks nobody.
                LocalRecommendations.accountCandidates = null
                assertTrue(LocalRecommendations.accountFor(seed).isEmpty())
                assertEquals(1, asked.get())
            } finally { LocalRecommendations.accountCandidates = previous }
            assertTrue(seen.isNotEmpty())
        }
    }
}
