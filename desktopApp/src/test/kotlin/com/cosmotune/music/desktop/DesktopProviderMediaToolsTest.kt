package com.cosmotune.music.desktop

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopProviderMediaToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = temporary.newFile(name).also { output ->
        requireNotNull(javaClass.getResourceAsStream("/provider-media/$name")).use { input -> output.outputStream().use(input::copyTo) }
    }
    private fun samples(file: File): ByteArray {
        val decoder = DesktopAudioDecoder()
        return try {
            decoder.open(file.absolutePath, requested = DesktopPcmFormat(44_100, 1, 2)).getOrThrow()
            java.io.ByteArrayOutputStream().use { out ->
                while (true) { val buffer = decoder.read() ?: break; out.write(buffer) }
                out.toByteArray()
            }
        } finally { decoder.close() }
    }
    @Test fun providerSuppliedKeyProducesTheSameDecodedAudioAsTheClearRecording() {
        val clear = fixture("clear.m4a"); val encrypted = fixture("encrypted.m4a"); val output = temporary.newFile("prepared.m4a")
        DesktopProviderMediaTools.remux(encrypted, output, "00112233445566778899aabbccddeeff", 1_000_000) { false }
        val expected = samples(clear); val actual = samples(output)
        assertTrue(expected.size > 80_000)
        assertContentEquals(expected, actual)
        val info = DesktopProviderMediaTools.probe(output) { false }
        assertEquals("aac", info.codec); assertEquals(44_100, info.sampleRate)
        assertTrue(info.durationMs in 990..1050)
    }
    @Test fun cancellationQuotaAndWrongKeysDoNotProducePlayableSuccess() {
        val encrypted = fixture("encrypted.m4a")
        assertFails { DesktopProviderMediaTools.remux(encrypted, temporary.newFile("cancelled.m4a"), "00112233445566778899aabbccddeeff", 1_000_000) { true } }
        assertFails { DesktopProviderMediaTools.remux(encrypted, temporary.newFile("quota.m4a"), "00112233445566778899aabbccddeeff", 256) { false } }
        assertFails { DesktopProviderMediaTools.remux(encrypted, temporary.newFile("wrong.m4a"), "00000000000000000000000000000000", 1_000_000) { false } }
    }
    @Test fun losslessMovPreparationPreserves24BitDecodedAudio() {
        val clear = fixture("clear.flac"); val encrypted = fixture("encrypted-flac.mp4"); val output = temporary.newFile("prepared.flac")
        DesktopProviderMediaTools.remux(encrypted, output, "00112233445566778899aabbccddeeff", 1_000_000) { false }
        val info = DesktopProviderMediaTools.probe(output) { false }
        assertEquals("flac", info.codec); assertEquals(24, info.bitDepth); assertEquals(44_100, info.sampleRate)
        assertContentEquals(samples(clear), samples(output))
    }
}
