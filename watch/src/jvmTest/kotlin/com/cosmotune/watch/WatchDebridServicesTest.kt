package com.cosmotune.watch

import com.cosmotune.watch.debrid.WatchDebridClient
import com.cosmotune.watch.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import kotlin.test.*

/**
 * AllDebrid and TorBox against loopback fixtures shaped like their public APIs. No account was
 * exercised: these prove the request sequence, file choice and failure handling, not that either
 * service accepts a given torrent today.
 */
class WatchDebridServicesTest {
    private val stream = Stream(StreamSource("test", "An add-on", "ADD"), "A copy", url = "magnet:?xt=urn:btih:${"b".repeat(40)}",
        protocol = StreamProtocol.PROGRESSIVE, filename = "episode-2.mkv")

    @Test fun allDebridUnlocksOnlyTheNamedFileOfAPackInsideFolders() = runBlocking<Unit> {
        TestServer().use { server ->
            server.on("/v4/magnet/upload") { MockResponse().setBody("""{"status":"success","data":{"magnets":[{"id":123,"ready":true}]}}""") }
            server.on("/v4.1/magnet/files") { request ->
                assertEquals("id%5B%5D=123", request.body.readUtf8())
                MockResponse().setBody("""{"status":"success","data":{"magnets":[{"id":"123","files":[{"n":"Season 1","e":[
                    {"n":"episode-1.mkv","s":900000000,"l":"https://alldebrid.com/f/one"},
                    {"n":"episode-2.mkv","s":800000000,"l":"https://alldebrid.com/f/two"},
                    {"n":"sample.txt","s":10,"l":"https://alldebrid.com/f/txt"}]}]}]}}""")
            }
            server.on("/v4/link/unlock") { request ->
                assertEquals("https://alldebrid.com/f/two", request.requestUrl!!.queryParameter("link"))
                MockResponse().setBody("""{"status":"success","data":{"link":"https://cdn.example.com/episode-2.mkv"}}""")
            }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.ALL_DEBRID to server.base))
            assertEquals("https://cdn.example.com/episode-2.mkv", client.resolve(WatchDebrid.ALL_DEBRID, "ad-token", stream))
            assertTrue(server.requests.all { it.getHeader("Authorization") == "Bearer ad-token" })
            // Nothing deletes the transfer: it may be the listener's own.
            assertTrue(server.paths().none { "delete" in it })
        }
    }

    @Test fun allDebridRefusesToGuessInAPackWithoutAFilename() = runBlocking<Unit> {
        TestServer().use { server ->
            server.on("/v4/magnet/upload") { MockResponse().setBody("""{"status":"success","data":{"magnets":[{"id":5}]}}""") }
            server.on("/v4.1/magnet/files") { MockResponse().setBody("""{"status":"success","data":{"magnets":[{"files":[
                {"n":"episode-1.mkv","l":"https://alldebrid.com/f/one"},{"n":"episode-2.mkv","l":"https://alldebrid.com/f/two"}]}]}}""") }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.ALL_DEBRID to server.base))
            assertFailsWith<IllegalArgumentException> { client.resolve(WatchDebrid.ALL_DEBRID, "ad-token", stream.copy(filename = null)) }
            assertTrue(server.paths().none { it.startsWith("/v4/link/unlock") }, "no link was unlocked")
        }
    }

    @Test fun allDebridReportsAnAccountErrorWithoutTheToken() = runBlocking<Unit> {
        TestServer().use { server ->
            server.on("/v4/magnet/upload") { MockResponse().setBody("""{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"The auth apikey is invalid"}}""") }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.ALL_DEBRID to server.base))
            val failure = assertFails { client.resolve(WatchDebrid.ALL_DEBRID, "secret-token", stream) }
            assertFalse(failure.message.orEmpty().contains("secret-token"))
        }
    }

    @Test fun torBoxWaitsUntilReadyThenRequestsTheNamedFile() = runBlocking<Unit> {
        TestServer().use { server ->
            server.on("/torrents/createtorrent") { request ->
                assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
                MockResponse().setBody("""{"success":true,"data":{"torrent_id":77}}""")
            }
            var polls = 0
            server.on("/torrents/mylist") { request ->
                assertEquals("77", request.requestUrl!!.queryParameter("id"))
                polls++
                if (polls == 1) MockResponse().setBody("""{"success":true,"data":{"download_ready":false,"files":[]}}""")
                else MockResponse().setBody("""{"success":true,"data":{"download_ready":true,"files":[{"id":0,"name":"Show/episode-1.mkv"},{"id":1,"name":"Show/episode-2.mkv"}]}}""")
            }
            server.on("/torrents/requestdl") { request ->
                val url = request.requestUrl!!
                assertEquals("1", url.queryParameter("file_id"))
                assertEquals("false", url.queryParameter("zip_link"))
                MockResponse().setBody("""{"success":true,"data":"https://cdn.example.com/e2.mkv"}""")
            }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.TORBOX to server.base))
            assertEquals("https://cdn.example.com/e2.mkv", client.resolve(WatchDebrid.TORBOX, "tb-token", stream))
            assertEquals(2, polls)
        }
    }

    @Test fun anInsecureAddressFromAServiceIsRefused() = runBlocking<Unit> {
        TestServer().use { server ->
            server.on("/torrents/createtorrent") { MockResponse().setBody("""{"success":true,"data":{"torrent_id":8}}""") }
            server.on("/torrents/mylist") { MockResponse().setBody("""{"success":true,"data":{"download_ready":true,"files":[{"id":3,"name":"episode-2.mkv"}]}}""") }
            server.on("/torrents/requestdl") { MockResponse().setBody("""{"success":true,"data":"http://cdn.example.com/e2.mkv"}""") }
            val client = WatchDebridClient(OkHttpClient(), mapOf(WatchDebrid.TORBOX to server.base))
            assertFailsWith<IllegalArgumentException> { client.resolve(WatchDebrid.TORBOX, "tb-token", stream) }
        }
    }
}
