package com.cosmotune.music

import com.cosmotune.music.data.sources.SourceCatalogue
import com.cosmotune.music.data.sources.SourceConfig
import com.cosmotune.music.data.sources.SourceHealth
import com.cosmotune.music.data.sources.SourceKind
import com.cosmotune.music.data.sources.SourceRegistry
import com.cosmotune.music.data.sources.SourceResolver
import com.cosmotune.music.data.sources.SubsonicSource
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.providers.ProviderBrowseKey
import com.cosmotune.providers.SubsonicCatalogue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** A Subsonic server registered through the Android source registry, against a real loopback HTTP server. */
class SubsonicSourceTest {
    private val before = SourceRegistry.configs.value
    private val server = okhttp3.mockwebserver.MockWebServer()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    init {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val url = request.requestUrl!!
                val q = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
                val path = url.encodedPath
                requests += path.substringAfterLast('/')
                val ok = q["u"] == "vijay" && q["t"] == md5("sesame" + q["s"])
                val body = when {
                    !ok -> """{"subsonic-response":{"status":"failed","error":{"code":40}}}"""
                    path.endsWith("ping.view") -> """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true}}"""
                    path.endsWith("getOpenSubsonicExtensions.view") -> """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[]}}"""
                    path.endsWith("search3.view") -> """{"subsonic-response":{"status":"ok","searchResult3":{"song":[
                        {"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","album":"Bombay","albumId":"al-1","duration":335,"suffix":"flac"}]}}}"""
                    path.endsWith("getAlbum.view") -> """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Bombay","artist":"A. R. Rahman",
                        "song":[{"id":"tr-1","title":"Kannalane","artist":"A. R. Rahman","duration":335,"suffix":"flac"},{"id":"tr-2","title":"Humma","artist":"A. R. Rahman","duration":300,"suffix":"mp3"}]}}}"""
                    path.endsWith("getSong.view") -> """{"subsonic-response":{"status":"ok","song":{"id":"${q["id"]}","suffix":"flac","samplingRate":48000,"bitDepth":24,"duration":335}}}"""
                    else -> """{"subsonic-response":{"status":"failed","error":{"code":70}}}"""
                }
                return okhttp3.mockwebserver.MockResponse().setBody(body)
            }
        }
        server.start()
    }

    private val port get() = server.port

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
        server.shutdown()
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a registered subsonic server searches browses and streams through the registry`() = runBlocking {
        SourceRegistry.configs.value = listOf(SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false), SourceConfig(kind = SourceKind.YOUTUBE))
        val config = SourceConfig(
            kind = SourceKind.SUBSONIC, label = "Home server",
            // Typed with /rest/ and a trailing slash; stored as the server root.
            baseUrl = "http://127.0.0.1:${port}/rest/",
            userSettings = mapOf(SubsonicCatalogue.KEY_USER to "vijay", SubsonicCatalogue.KEY_PASSWORD to "sesame"),
        )
        SourceRegistry.add(config)
        assertEquals("http://127.0.0.1:${port}", SourceRegistry.config(config.id)!!.baseUrl)
        val source = SourceRegistry.instance(config.id) as SubsonicSource
        assertTrue(SourceRegistry.active().first() === source) // user-added: asked before JioSaavn and YouTube
        assertEquals(SourceHealth.Ok("navidrome 1.16.1"), source.health())

        val songs = source.search("kanna")
        assertEquals(listOf(SourceRegistry.trackKey(config.id, "tr-1")), songs.map { it.videoId })

        val albumKey = ProviderBrowseKey.parse(songs.single().albumId!!)!!
        val album = SourceCatalogue.load(albumKey)
        assertEquals("Bombay", album.collection.title)
        assertEquals(listOf("Kannalane", "Humma"), album.songs.map { it.title })

        val target = TrackMatcher.Target(title = "Kannalane", artist = "A. R. Rahman", durationSec = 335)
        val stream = SourceResolver.resolve(config.id, "tr-2", target)!!
        assertEquals(config.id, stream.sourceConfigId)
        assertEquals("tr-2", stream.url.toHttpUrl().queryParameter("id"))
        assertEquals(true, stream.format.isLossless)
        assertEquals(24, stream.format.bitDepth)
        assertFalse(stream.url.contains("sesame"))
        assertTrue("getSong.view" in requests)
    }

    @Test
    fun `incomplete or disabled servers are not asked`() {
        SourceRegistry.configs.value = listOf(SourceConfig(kind = SourceKind.YOUTUBE))
        val noPassword = SourceConfig(kind = SourceKind.SUBSONIC, baseUrl = "http://127.0.0.1:${port}",
            userSettings = mapOf(SubsonicCatalogue.KEY_USER to "vijay"))
        SourceRegistry.add(noPassword)
        assertFalse(noPassword.isComplete)
        assertTrue(SourceRegistry.active().none { it.kind == SourceKind.SUBSONIC })
        val complete = noPassword.copy(userSettings = noPassword.userSettings + (SubsonicCatalogue.KEY_PASSWORD to "sesame"))
        SourceRegistry.update(complete)
        assertTrue(SourceRegistry.active().any { it.kind == SourceKind.SUBSONIC })
        SourceRegistry.setEnabled(complete.id, false)
        assertTrue(SourceRegistry.active().none { it.kind == SourceKind.SUBSONIC })
        assertTrue(requests.isEmpty())
    }
}
