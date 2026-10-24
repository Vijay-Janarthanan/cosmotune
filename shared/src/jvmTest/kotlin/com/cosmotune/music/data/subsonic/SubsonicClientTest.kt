package com.cosmotune.music.data.subsonic

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.*
import org.junit.After
import org.junit.Test

/** Against a real loopback HTTP server shaped like a Navidrome/OpenSubsonic response. */
class SubsonicClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seen = mutableListOf<Map<String, String>>()
    private var searchBody = """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[
        {"id":"tr-1","title":"Kannalane","artist":"Chitra","album":"Bombay","duration":335,"suffix":"flac","bitRate":1011},
        {"id":"vid","title":"Clip","isVideo":true},
        {"id":"tr-2","title":"Rakkamma","artist":"S. P. Balasubrahmanyam","duration":402,"suffix":"mp3","bitRate":320}]}}}"""

    init {
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            synchronized(seen) { seen += query + ("path" to exchange.requestURI.path) }
            val user = query["u"]; val ok = (user == "vijay" && query["t"] == SubsonicClient.md5("sesame" + query["s"])) || query["apiKey"] == "key-123"
            val (code, body) = when {
                exchange.requestURI.path.endsWith("/redirect/rest/ping.view") -> 302 to ""
                exchange.requestURI.path.endsWith("/huge/rest/ping.view") -> 200 to "x".repeat(5 * 1024 * 1024)
                !ok -> 200 to """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
                exchange.requestURI.path.endsWith("ping.view") -> 200 to """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true}}"""
                exchange.requestURI.path.endsWith("getOpenSubsonicExtensions.view") -> 200 to """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[{"name":"apiKeyAuthentication","versions":[1]},{"name":"songLyrics","versions":[1]}]}}"""
                exchange.requestURI.path.endsWith("search3.view") -> 200 to searchBody
                exchange.requestURI.path.endsWith("getAlbum.view") -> 200 to """{"subsonic-response":{"status":"ok","album":{"id":"al","song":{"id":"only","title":"Solo","artist":"A","duration":61}}}}"""
                else -> 200 to """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"""
            }
            if (code == 302) exchange.responseHeaders.add("Location", "http://example.invalid/")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        server.start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/music/"
    private val http = OkHttpClient()

    @After fun stop() = server.stop(0)

    @Test fun tokenSignInSearchAndAlbumsMapToSourceQualifiedSongs() = runBlocking<Unit> {
        val client = SubsonicClient(base, "vijay", SubsonicCredential.Password("sesame"), http)
        val info = client.ping()
        assertEquals("navidrome", info.type); assertTrue(info.openSubsonic); assertTrue("apiKeyAuthentication" in info.extensions)
        val songs = client.search("kanna")
        assertEquals(listOf("Kannalane", "Rakkamma"), songs.map { it.title })
        assertEquals("subsonic:${client.serverKey}:tr-1", songs[0].videoId)
        assertEquals("5:35", songs[0].durationText); assertEquals("FLAC 1011 kbps", songs[0].sourceQuality); assertEquals("Bombay", songs[0].albumName)
        assertEquals("tr-2", client.ownsTrack(songs[1].videoId)); assertNull(client.ownsTrack("subsonic:other:tr-2"))
        assertEquals(listOf("Solo"), client.album("al").map { it.title }) // a single object is accepted as a one-song list
        // Every request used a fresh salt and never the plain password.
        val salts = synchronized(seen) { seen.mapNotNull { it["s"] } }
        assertEquals(salts.size, salts.toSet().size)
        assertTrue(synchronized(seen) { seen.none { q -> q.values.any { it.contains("sesame") } } })
        assertTrue(synchronized(seen) { seen.all { it["path"]!!.startsWith("/music/rest/") && it["f"] == "json" && it["c"] == "Cosmotune" } })
        val stream = client.streamUrl("tr-1").toHttpUrl()
        assertEquals("tr-1", stream.queryParameter("id")); assertEquals("raw", stream.queryParameter("format")); assertNotNull(stream.queryParameter("t"))
    }

    @Test fun apiKeysWrongPasswordsRedirectsAndOversizedResponsesAreHandled() = runBlocking<Unit> {
        assertEquals("1.16.1", SubsonicClient(base, "", SubsonicCredential.ApiKey("key-123"), http).ping().version)
        val wrong = assertFailsWith<SubsonicException> { SubsonicClient(base, "vijay", SubsonicCredential.Password("nope"), http).ping() }
        assertEquals(40, wrong.code); assertEquals("Wrong user name or password.", wrong.message)
        val port = server.address.port
        assertFailsWith<SubsonicException> { SubsonicClient("http://127.0.0.1:$port/redirect", "vijay", SubsonicCredential.Password("sesame"), http).ping() }
        assertEquals("The server response is too large.", assertFailsWith<SubsonicException> {
            SubsonicClient("http://127.0.0.1:$port/huge", "vijay", SubsonicCredential.Password("sesame"), http).ping() }.message)
        assertEquals(70, assertFailsWith<SubsonicException> { SubsonicClient(base, "vijay", SubsonicCredential.Password("sesame"), http).randomSongs() }.code)
        assertNull(SubsonicClient.normalize("http://user:pw@host/"))
        assertNull(SubsonicClient.normalize("ftp://host/"))
        assertEquals("http://host:4533/music", SubsonicClient.normalize("http://host:4533/music/rest/").toString())
        assertFailsWith<IllegalArgumentException> { SubsonicClient("not a url", "vijay", SubsonicCredential.Password("x"), http) }
        assertFalse(SubsonicCredential.Password("secret").toString().contains("secret"))
    }
}
