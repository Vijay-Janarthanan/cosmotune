package com.cosmotune.music

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cosmotune.providers.AndroidProviderMediaTools
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ProviderMediaToolsTest {
    @Test fun losslessMovAudioKeepsIts24BitFormatAndOriginalPackets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "provider-lossless-test").apply { mkdirs() }
        try {
            val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            val input = File(root, "encrypted-flac.mp4"); val clear = File(root, "clear.flac"); val output = File(root, "prepared.flac")
            for (file in listOf(input, clear)) assets.open("provider-media/${file.name}").use { source -> file.outputStream().use(source::copyTo) }
            AndroidProviderMediaTools.remux(input, output, "00112233445566778899aabbccddeeff", 1_000_000) { false }
            val info = AndroidProviderMediaTools.probe(output) { false }
            assertEquals("flac", info.codec); assertEquals(24, info.bitDepth); assertEquals(44_100, info.sampleRate)
            assertArrayEquals(packets(clear), packets(output))
        } finally { root.deleteRecursively() }
    }
    @Test fun encryptedAudioIsPreparedWithTheShippedNativeLibrary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "provider-media-test").apply { mkdirs() }
        try {
            val input = File(root, "encrypted.m4a")
            val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            assets.open("provider-media/encrypted.m4a").use { source -> input.outputStream().use(source::copyTo) }
            val output = File(root, "prepared.m4a")
            AndroidProviderMediaTools.remux(input, output, "00112233445566778899aabbccddeeff", 1_000_000) { false }
            val info = AndroidProviderMediaTools.probe(output) { false }
            assertEquals("aac", info.codec); assertEquals(44_100, info.sampleRate); assertTrue(info.durationMs in 990..1050)
            val clear = File(root, "clear.m4a")
            assets.open("provider-media/clear.m4a").use { source -> clear.outputStream().use(source::copyTo) }
            assertArrayEquals(packets(clear), packets(output))
            for (case in listOf("cancel", "quota", "wrong")) {
                var failed = false
                try { AndroidProviderMediaTools.remux(input, File(root, "$case.m4a"),
                    if (case == "wrong") "00000000000000000000000000000000" else "00112233445566778899aabbccddeeff",
                    if (case == "quota") 256 else 1_000_000) { case == "cancel" } }
                catch (_: IllegalStateException) { failed = true }
                assertTrue("$case must fail", failed)
            }
        } finally { root.deleteRecursively() }
    }
    private fun packets(file: File): ByteArray {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(0)
            val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
            java.io.ByteArrayOutputStream().use { out ->
                while (true) {
                    buffer.clear(); val count = extractor.readSampleData(buffer, 0); if (count < 0) break
                    val bytes = ByteArray(count); buffer.position(0); buffer.get(bytes); out.write(bytes); extractor.advance()
                }
                out.toByteArray()
            }
        } finally { extractor.release() }
    }
}
