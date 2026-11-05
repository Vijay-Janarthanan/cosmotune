package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.ProviderBrowseKey
import com.cosmotune.providers.SubsonicCatalogue
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Subsonic server registered in the desktop source registry, against a real loopback HTTP
 * server. The registry reads an injected source list and ceiling, never the user's preferences.
 */
class DesktopSubsonicSourceTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val previousConfigs = DesktopSourceRegistry.configSource
    private val previousQuality = DesktopSourceRegistry.qualitySource

    init {
        server.createContext("/") { exchange ->
            val q = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val path = exchange.requestURI.path
            val body = when {
                q["apiKey"] != "key-123" -> """{"subsonic-response":{"status":"failed","error":{"code":40}}}"""
                path.endsWith("search3.view") -> """{"subsonic-response":{"status":"ok","searchResult3":{"song":[
                    {"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","album":"Bombay","albumId":"al-1","duration":335,"suffix":"flac"}]}}}"""
                path.endsWith("getAlbum.view") -> """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Bombay","artist":"A. R. Rahman",
                    "song":[{"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","duration":335,"suffix":"flac"}]}}}"""
                path.endsWith("getSong.view") -> """{"subsonic-response":{"status":"ok","song":{"id":"tr-1","suffix":"flac","samplingRate":44100,"bitDepth":16,"duration":335}}}"""
                else -> """{"subsonic-response":{"status":"failed","error":{"code":70}}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val subsonic = DesktopSourceConfig(
        id = "home-server", kind = DesktopSourceKind.SUBSONIC, label = "Home server",
        baseUrl = "http://127.0.0.1:${server.address.port}",
        userSettings = mapOf(SubsonicCatalogue.KEY_API_KEY to "key-123"),
    )

    private fun install(vararg configs: DesktopSourceConfig) {
        DesktopSourceRegistry.configSource = { configs.toList() }
        DesktopSourceRegistry.qualitySource = { DesktopAudioQuality.LOSSLESS }
    }

    @AfterTest
    fun tearDown() {
        DesktopSourceRegistry.configSource = previousConfigs
        DesktopSourceRegistry.qualitySource = previousQuality
        server.stop(0)
    }

    @Test
    fun searchBrowseAndStreamGoThroughTheRegistry() = runBlocking {
        install(subsonic, DesktopSourceConfig("youtube", DesktopSourceKind.YOUTUBE))
        assertTrue(subsonic.isComplete && subsonic.isUserAdded)
        assertEquals("Home server", DesktopSourceRegistry.descriptors().first().name)
        val catalogue = assertNotNull(DesktopSourceRegistry.subsonic(subsonic))

        val row = catalogue.searchSongs("kanna").single()
        assertEquals("src:home-server::tr-1", row.videoId)
        assertFalse(DesktopSourceRegistry.hasYouTubeOriginal(row))

        val page = DesktopSourceCatalogue.load(ProviderBrowseKey.parse(assertNotNull(row.albumId))!!)
        assertEquals("Bombay", page.collection.title)
        assertEquals(listOf(row.videoId), page.songs.map { it.videoId })

        val stream = DesktopSourceRegistry.resolveLive(row, "LOSSLESS").getOrThrow().stream
        assertEquals("home-server", stream.sourceId)
        assertEquals("flac", stream.format.codec)
        assertTrue(stream.format.isLossless)
        assertEquals("tr-1", stream.url.toHttpUrl().queryParameter("id"))

    }

    @Test
    fun aDisabledOrIncompleteServerIsNotAsked() = runBlocking {
        install(subsonic.copy(enabled = false), DesktopSourceConfig("youtube", DesktopSourceKind.YOUTUBE))
        val row = Song(videoId = "src:home-server::tr-1", title = "Kannalane", artist = "A. R. Rahman", thumbnailUrl = null)
        assertTrue(DesktopSourceRegistry.resolve(row, "LOSSLESS").isFailure)
        assertFalse(subsonic.copy(userSettings = emptyMap()).isComplete)
        assertFalse(subsonic.copy(baseUrl = "ftp://host").isComplete)
    }
}
