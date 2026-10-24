package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Opt-in metadata only. No provider package or audio is bundled/downloaded by the ordinary test suite. */
class ProviderLiveMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun publicJioSaavnSourceSearchAlbumAndHome() = runBlocking<Unit> {
        val audio = System.getProperty("cosmotune.provider.live") == "jiosaavn-audio"
        assumeTrue(audio || System.getProperty("cosmotune.provider.live") == "jiosaavn")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "build/upstream/Mornye-Extension/sources/jiosaavn")
        require(source.isDirectory) { "The pinned Mornye extension source checkout is required" }
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json", "index.js").forEach { name ->
                zip.putNextEntry(ZipEntry(name)); File(source, name).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            } }
            output.toByteArray()
        }
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            try {
                manager.trust(manager.inspect { bytes.inputStream() })
                manager.saveSettings("jiosaavn", buildJsonObject { put("lang", "tamil") })
                val tracks = manager.search("jiosaavn", "Ilaiyaraaja", 10)
                assertTrue(tracks.isNotEmpty())
                assertTrue(tracks.all { it.id.isNotBlank() && it.title.isNotBlank() })
                val albumKey = tracks.firstNotNullOfOrNull { it.albumId } ?: error("Search returned no album identity")
                val album = manager.catalogue(ProviderBrowseKey("jiosaavn", "album", albumKey))
                assertTrue(album.tracks.isNotEmpty())
                val sections = manager.home("jiosaavn", "IN", "tamil")
                assertTrue(sections.isNotEmpty())
                println("Live Mornye JioSaavn metadata: ${tracks.size} tracks; album ${album.tracks.size} tracks; ${sections.size} home sections.")
                if (audio) {
                    val stream = manager.stream("jiosaavn", tracks.first().id, lossless = true)
                    assertEquals("aac", stream.codec)
                    assertEquals(320, stream.kbps)
                    okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(stream.url).build()).execute().use { reply ->
                        assertTrue(reply.isSuccessful)
                        val bytes = reply.body.source().readByteArray(65_536)
                        assertEquals("ftyp", bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
                    }
                    val ffmpeg = System.getProperty("cosmotune.provider.ffmpeg").takeIf { it.isNotBlank() }
                    if (ffmpeg != null) {
                        require(File(ffmpeg).isFile)
                        val log = temporary.newFile("decoder.log")
                        val decoder = ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-i", stream.url, "-t", "1", "-f", "null", "-")
                            .redirectErrorStream(true).redirectOutput(log).start()
                        try {
                            assertTrue(decoder.waitFor(45, java.util.concurrent.TimeUnit.SECONDS), "Live provider decoder exceeded its time budget")
                            assertEquals(0, decoder.exitValue(), "Live provider audio did not decode")
                        } finally { if (decoder.isAlive) decoder.destroyForcibly() }
                        println("Live provider audio: MP4 signature and one second of FFmpeg audio decoding passed through the checked loopback transport.")
                    } else println("Live provider audio: MP4 signature verified; decoder executable was not configured.")
                } else println("Metadata-only check: no audio requested.")
            } finally { manager.remove("jiosaavn") }
        }
    }
}
