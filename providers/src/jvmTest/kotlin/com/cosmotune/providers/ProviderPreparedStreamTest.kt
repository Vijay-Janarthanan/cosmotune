package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/**
 * MN-04 file-only playback: a provider that only implements the SDK's `download(trackId, quality,
 * outputPath)` writes a file into its workspace, and the player receives a loopback address that
 * serves exactly those bytes, with ranges, until the provider is disabled.
 */
class ProviderPreparedStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    /** A small FLAC-signature payload: the container is read from the bytes, not the suffix. */
    private val audio = ByteArray(60_000) { (it % 251).toByte() }.also { "fLaC".toByteArray().copyInto(it) }

    private fun packageBytes(suffix: String, filePermission: Boolean = true, directStub: Boolean = false, requiresProcessing: Boolean = false): ByteArray = ByteArrayOutputStream().use { output ->
        val manifest = """{"name":"fileonly","version":"1.0.0","type":["download_provider"],"permissions":{"network":[],"file":$filePermission},
            "qualityOptions":[{"id":"LOSSLESS","label":"FLAC","kind":"lossless"}]}"""
        val encoded = Base64.getEncoder().encodeToString(audio)
        val script = """
            registerExtension({
              ${if (directStub && requiresProcessing) "getDownloadUrl:function(){return {url:'https://example.com/audio.flac',decryption_key:'fixture-key'};}," else if (directStub) "getDownloadUrl:function(){return null;}," else ""}
              download:function(trackId,quality,outputPath,onProgress,options){
                ${if (directStub) "if(typeof onProgress!=='function' || !options || !options.purpose)return {success:false};" else ""}
                if(trackId!=='track')return {success:false};
                var path=outputPath.replace(/\.[^.]+$/,'.$suffix');
                file.writeBytes(path,'$encoded');
                return {success:true,file_path:path,quality:'FLAC'${if (requiresProcessing) ",decryption:{strategy:'ffmpeg.mov_key',key:'fixture-key'}" else ""}};
              }
            });
        """.trimIndent()
        ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    private val http = OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

    @Test fun aNullDirectUrlFallsBackToThePreparedFileWithCorrectSdkArguments() = runBlocking<Unit> {
        ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
            manager.trust(manager.inspect { packageBytes("m4a", directStub = true).inputStream() })
            val stream = manager.stream("fileonly", "track", lossless = true)
            assertEquals("flac", stream.codec)
            http.newCall(Request.Builder().url(stream.url).build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertContentEquals(audio, response.body.bytes())
            }
        }
    }

    @Test fun anEncryptedPreparedFileIsNotOfferedAsReadyAudioOrLabelledLossless() = runBlocking<Unit> {
        for (direct in listOf(false, true)) {
            val root = temporary.newFolder()
            ProviderManager(ProviderStore(root, UnsealedProviderFiles)).use { manager ->
                manager.trust(manager.inspect { packageBytes("flac", directStub = direct, requiresProcessing = true).inputStream() })
                val failure = assertFailsWith<ProviderResolutionException> { manager.stream("fileonly", "track", lossless = true) }
                assertEquals("native_processing_required", failure.errorType)
                assertFalse(failure.message.orEmpty().contains("fixture-key"))
                assertFalse(java.io.File(root, "workspaces/fileonly/.cosmotune-media").walk().any { it.isFile })
            }
        }
    }

    @Test fun aFileOnlyProviderIsServedWithRangesAndItsCopyIsReleasedOnDisable() = runBlocking<Unit> {
        val root = temporary.newFolder()
        ProviderManager(ProviderStore(root, UnsealedProviderFiles)).use { manager ->
            // A legacy provider leaving a different suffix on FLAC bytes must still be labelled FLAC.
            manager.trust(manager.inspect { packageBytes("m4a").inputStream() })
            val stream = manager.stream("fileonly", "track", lossless = true)
            assertEquals("flac", stream.codec)
            assertEquals("flac", stream.container)
            assertTrue(stream.url.startsWith("http://127.0.0.1:"), "the decoder gets a loopback address, never a workspace path")
            assertFalse(root.absolutePath in stream.url)

            http.newCall(Request.Builder().url(stream.url).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("audio/flac", response.header("Content-Type"))
                assertContentEquals(audio, response.body.bytes())
            }
            http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=1000-1999").build()).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals("bytes 1000-1999/${audio.size}", response.header("Content-Range"))
                assertContentEquals(audio.copyOfRange(1000, 2000), response.body.bytes())
            }
            http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=-10").build()).execute().use { response ->
                assertEquals(206, response.code)
                assertContentEquals(audio.copyOfRange(audio.size - 10, audio.size), response.body.bytes())
            }
            http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=${audio.size}-").build()).execute().use { response ->
                assertEquals(416, response.code)
            }

            val media = java.io.File(root, "workspaces/fileonly/.cosmotune-media")
            assertTrue(media.walk().any { it.isFile && it.length() == audio.size.toLong() })
            manager.setEnabled("fileonly", false)
            http.newCall(Request.Builder().url(stream.url).build()).execute().use { assertEquals(502, it.code) }
            assertFalse(media.walk().any { it.isFile && it.length() == audio.size.toLong() }, "the leased copy is deleted with the lease")
        }
    }

    @Test fun aRefusedRecordingOrAMissingFilePermissionFailsWithoutALease() = runBlocking<Unit> {
        val root = temporary.newFolder()
        ProviderManager(ProviderStore(root, UnsealedProviderFiles)).use { manager ->
            manager.trust(manager.inspect { packageBytes("flac").inputStream() })
            assertFailsWith<Exception> { manager.stream("fileonly", "other", lossless = true) }
            val media = java.io.File(root, "workspaces/fileonly/.cosmotune-media")
            assertFalse(media.walk().any { it.isFile && it.name.startsWith("audio") })
        }
        ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
            manager.trust(manager.inspect { packageBytes("flac", filePermission = false).inputStream() })
            val failure = assertFailsWith<Exception> { manager.stream("fileonly", "track", lossless = true) }
            assertEquals("This provider does not expose a direct audio stream", failure.message)
        }
    }
}
