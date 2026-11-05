package com.cosmotune.providers

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.*
import org.junit.After
import org.junit.Test

/** The Subsonic catalogue adapter against a real loopback server shaped like Navidrome's answers. */
class SubsonicCatalogueTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val q = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val ok = q["u"] == "vijay" && q["t"] == md5("sesame" + q["s"])
            val path = exchange.requestURI.path
            val body = when {
                !ok -> """{"subsonic-response":{"status":"failed","error":{"code":40}}}"""
                path.endsWith("search3.view") -> """{"subsonic-response":{"status":"ok","searchResult3":{
                    "artist":[{"id":"ar-1","name":"A. R. Rahman"}],
                    "album":[{"id":"al-1","name":"Bombay","artist":"A. R. Rahman","artistId":"ar-1"}],
                    "song":[{"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","artistId":"ar-1","album":"Bombay","albumId":"al-1","duration":335,"suffix":"flac","bitRate":1011},
                            {"id":"vid","title":"Clip","isVideo":true}]}}}"""
                path.endsWith("getAlbum.view") && q["id"] == "al-1" -> """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Bombay","artist":"A. R. Rahman",
                    "song":[{"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","duration":335,"suffix":"flac"},{"id":"tr-2","title":"Humma","artist":"A. R. Rahman","duration":300,"suffix":"mp3"}]}}}"""
                path.endsWith("getArtist.view") && q["id"] == "ar-1" -> """{"subsonic-response":{"status":"ok","artist":{"id":"ar-1","name":"A. R. Rahman",
                    "album":{"id":"al-1","name":"Bombay","artist":"A. R. Rahman"}}}}"""
                path.endsWith("getSong.view") && q["id"] == "tr-1" -> """{"subsonic-response":{"status":"ok","song":{"id":"tr-1","title":"Kannalane","suffix":"FLAC","bitRate":1011,"samplingRate":44100,"bitDepth":16,"duration":335}}}"""
                else -> """{"subsonic-response":{"status":"failed","error":{"code":70}}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val base = "http://127.0.0.1:${server.address.port}/"
    private val settings = mapOf(SubsonicCatalogue.KEY_USER to "vijay", SubsonicCatalogue.KEY_PASSWORD to "sesame")

    private fun md5(text: String) = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @After fun stop() = server.stop(0)

    @Test fun searchBrowseAndStreamUseSourceQualifiedIdsAndTheSharedCatalogueShape() = runBlocking<Unit> {
        val catalogue = assertNotNull(SubsonicCatalogue.forSource("sub-1", base, settings, OkHttpClient()))
        assertEquals(setOf("album", "artist"), catalogue.load("manifest", "")["resources"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())

        val songs = catalogue.searchSongs("kanna")
        assertEquals(listOf("src:sub-1::tr-1"), songs.map { it.videoId }) // the video row is left out
        assertEquals("LOSSLESS", songs.single().sourceQuality)
        assertEquals("5:35", songs.single().durationText)
        assertNull(songs.single().thumbnailUrl) // cover addresses carry credentials
        val albumKey = assertNotNull(ProviderBrowseKey.parse(assertNotNull(songs.single().albumId)))
        assertEquals(ProviderBrowseKey("sub-1", "album", "al-1", "addon"), albumKey)
        assertEquals("tr-1", catalogue.owns(songs.single().videoId)); assertNull(catalogue.owns("src:other::tr-1"))

        // The search answer feeds the shared catalogue browser: tracks, albums and artists.
        val search = catalogue.load("search", "kanna")
        assertEquals(1, ProviderCatalogue.tracks("sub-1", search, "addon").size)
        assertEquals("Bombay", ProviderCatalogue.collection("sub-1", "album", search["albums"]!!.jsonArray[0].jsonObject, namespace = "addon")?.title)

        val album = catalogue.page(albumKey)
        assertEquals("Bombay", album.collection.title)
        assertEquals(listOf("Kannalane", "Humma"), album.songs.map { it.title })
        assertEquals(listOf("LOSSLESS", "HIGH"), album.songs.map { it.sourceQuality })
        assertEquals(listOf("src:sub-1::tr-1", "src:sub-1::tr-2"), album.songs.map { it.videoId })

        val artist = catalogue.page(ProviderBrowseKey("sub-1", "artist", "ar-1", "addon"))
        assertEquals("A. R. Rahman", artist.collection.title)
        assertEquals(listOf("Bombay"), artist.collection.collections.map { it.title }) // a single album object is accepted

        val stream = catalogue.stream("tr-1")
        assertEquals("flac", stream.codec); assertTrue(stream.lossless); assertEquals(44100, stream.sampleRateHz); assertEquals(16, stream.bitDepth)
        val url = stream.url.toHttpUrl()
        assertEquals("tr-1", url.queryParameter("id")); assertEquals("raw", url.queryParameter("format"))
        assertFalse(stream.url.contains("sesame")); assertFalse(stream.toString().contains(url.queryParameter("t")!!))
    }

    @Test fun incompleteSettingsBuildNoCatalogue() {
        assertNull(SubsonicCatalogue.forSource("sub-1", base, mapOf(SubsonicCatalogue.KEY_USER to "vijay"), OkHttpClient()))
        assertFalse(SubsonicCatalogue.isComplete(base, mapOf(SubsonicCatalogue.KEY_PASSWORD to "x")))
        assertTrue(SubsonicCatalogue.isComplete(base, mapOf(SubsonicCatalogue.KEY_API_KEY to "k")))
        assertFalse(SubsonicCatalogue.isComplete("ftp://host", mapOf(SubsonicCatalogue.KEY_API_KEY to "k")))
        assertFailsWith<IllegalArgumentException> { runBlocking { SubsonicCatalogue.forSource("sub-1", base, settings, OkHttpClient())!!.page(ProviderBrowseKey("other", "album", "x", "addon")) } }
    }
}
