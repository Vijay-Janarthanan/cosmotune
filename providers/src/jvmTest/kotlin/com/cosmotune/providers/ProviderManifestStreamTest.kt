package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderManifestStreamTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun providerManifestOfferPublishesOnlyPreparedLocalAudioAndRevokesItsLease() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val archive = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    val manifest = """{"name":"manifest","version":"1.0.0","type":["download_provider"],"permissions":{"network":["localhost"],"allowHttp":true,"file":true}}"""
                    val script = "registerExtension({getDownloadUrl:function(){return {url:${JsonPrimitive(server.url("/list.m3u8").toString())},format:'hls',audio_codec:'flac'};}});"
                    for ((name, body) in listOf("manifest.json" to manifest, "index.js" to script)) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
                }; output.toByteArray()
            }
            val bytes = "fLaC${" audio".repeat(100)}"
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\npart\n#EXT-X-ENDLIST"))
            server.enqueue(MockResponse().setBody(bytes.take(4))); server.enqueue(MockResponse().setBody(bytes.drop(4)))
            val previous = ProviderMediaTools.installed
            ProviderMediaTools.installed = object : ProviderMediaTools {
                override fun probe(input: File, cancelled: () -> Boolean) = ProviderMediaInfo("flac", 44100, 2, 24, 1000, 100_000)
                override fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean) { assertNull(key); assertEquals(bytes, input.readText()); input.copyTo(output, overwrite = true) }
            }
            try {
                ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
                    manager.trust(manager.inspect { archive.inputStream() }); manager.saveSettings("manifest", JsonObject(emptyMap()), allowLocal = true)
                    val stream = manager.stream("manifest", "recording", lossless = true)
                    assertEquals("flac", stream.codec); assertEquals(24, stream.bitDepth)
                    assertTrue(stream.url.startsWith("http://127.0.0.1:"))
                    val client = OkHttpClient()
                    client.newCall(Request.Builder().url(stream.url).build()).execute().use { assertEquals(bytes, it.body.string()) }
                    manager.setEnabled("manifest", false)
                    client.newCall(Request.Builder().url(stream.url).build()).execute().use { assertEquals(502, it.code) }
                }
            } finally { ProviderMediaTools.installed = previous }
        }
    }
}
