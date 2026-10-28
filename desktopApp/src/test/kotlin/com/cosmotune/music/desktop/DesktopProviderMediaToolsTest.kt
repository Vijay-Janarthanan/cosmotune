package com.cosmotune.music.desktop

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopProviderMediaToolsTest {
    @Test fun nativeFlacEncodingPreservesVerifiedLosslessAudioAndRejectsLossyUpscaling() {
        val input = fixture("clear.flac"); val output = temporary.newFile("encoded.flac")
        DesktopProviderMediaTools.convertFlac(input, output, 2_000_000) { false }
        val info = DesktopProviderMediaTools.probe(output) { false }
        assertEquals("flac", info.codec); assertEquals(24, info.bitDepth); assertEquals(44_100, info.sampleRate)
        assertContentEquals(samples(input), samples(output))
        assertFails { DesktopProviderMediaTools.convertFlac(fixture("clear.m4a"), temporary.newFile("lossy.flac"), 2_000_000) { false } }
        assertFalse(output.parentFile.listFiles().orEmpty().any { it.name.startsWith("encoding-") })
    }
    @Test fun typedPcmConversionPreservesRateChannelsAndPlayableAudioAt16And24Bits() {
        val input = fixture("clear.flac")
        for (bits in listOf(16, 24)) {
            val output = temporary.newFile("converted-$bits.wav")
            DesktopProviderMediaTools.convertPcm(input, output, bits, 1_000_000) { false }
            val info = DesktopProviderMediaTools.probe(output) { false }
            assertEquals("pcm_s${bits}le", info.codec); assertEquals(bits, info.bitDepth)
            assertEquals(44_100, info.sampleRate); assertTrue(samples(output).isNotEmpty())
        }
        assertFails { DesktopProviderMediaTools.convertPcm(input, temporary.newFile("quota.wav"), 24, 256) { false } }
        assertFails { DesktopProviderMediaTools.convertPcm(input, temporary.newFile("cancel.wav"), 24, 1_000_000) { true } }
    }
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
