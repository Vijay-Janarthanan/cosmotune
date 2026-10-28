package com.cosmotune.music.desktop

import com.cosmotune.audio.OutputFidelity
import com.cosmotune.audio.OutputFidelityVerdict
import com.cosmotune.audio.OutputMixing
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class DesktopDirectOutputTest {
    @Test fun `float PCM retains finite headroom rather than applying a hidden limiter`() {
        val buffer = ByteBuffer.wrap(encodeDesktopPcm(floatArrayOf(1.25f, -1.5f), 2, DesktopPcmFormat(48_000, 2, 4, true), 1f)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.25f, buffer.float)
        assertEquals(-1.5f, buffer.float)
    }
    @Test fun `a rejected float offer is never reported as the accepted device format`() {
        val tried = mutableListOf<DesktopPcmFormat>()
        val accepted = negotiateDirectPcm(DesktopPcmFormat(96_000, 2, 4, true)) { candidate ->
            tried.add(candidate)
            if (candidate.bytesPerSample == 3) Result.success("opened") else Result.failure(IllegalStateException("not supported"))
        }.getOrThrow()
        assertEquals(3, tried.size)
        assertEquals(3, accepted.format.bytesPerSample)
        assertFalse(accepted.format.isFloat)
        assertEquals("opened", accepted.endpoint)
        assertTrue(negotiateDirectPcm<Unit>(accepted.format) { Result.failure(IllegalStateException("busy")) }.isFailure)
    }
    @Test fun `exact output candidates retain rate and channel layout`() {
        val candidates = directPcmCandidates(DesktopPcmFormat(96_000, 2, 4, true))
        assertEquals(listOf(4, 4, 3, 2), candidates.map { it.bytesPerSample })
        assertTrue(candidates.all { it.sampleRate == 96_000 && it.channels == 2 })
        assertEquals(listOf(true, false, false, false), candidates.map { it.isFloat })
    }

    @Test fun `PCM16 and PCM24 roundtrip canonical float samples without losing a low bit`() {
        val samples = floatArrayOf(-1f, -.5f, 0f, 1f / 32768, .5f, 1f)
        val s16 = ByteBuffer.wrap(encodeDesktopPcm(samples, samples.size, DesktopPcmFormat(48_000, 2, 2), 1f)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(-32768, -16384, 0, 1, 16384, 32767), List(samples.size) { s16.short.toInt() })
        val s24 = encodeDesktopPcm(floatArrayOf(-1f, 1f / 8_388_608, .5f, 1f), 4, DesktopPcmFormat(48_000, 2, 3), 1f)
        assertContentEquals(byteArrayOf(0, 0, -128, 1, 0, 0, 0, 0, 64, -1, -1, 127), s24)
    }

    @Test fun `PCM32 clips safely and invalid samples cannot become nonfinite output`() {
        val bytes = encodeDesktopPcm(floatArrayOf(-2f, -1f, .5f, 2f, Float.NaN), 5, DesktopPcmFormat(48_000, 2, 4), 1f)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(Int.MIN_VALUE, Int.MIN_VALUE, 1_073_741_824, Int.MAX_VALUE, 0), List(5) { buffer.int })
    }

    @Test fun `direct device ownership does not invent confirmed bit-perfect output`() {
        val pipeline = DesktopAudioPipeline(directOutput = true)
        assertEquals(OutputMixing.DIRECT_UNCONFIRMED, pipeline.fidelityFacts().mixing)
        assertIs<OutputFidelity.UnchangedByApp>(OutputFidelityVerdict.of(pipeline.fidelityFacts()))
        assertEquals(OutputFidelity.Altered("crossfade or Automix"), OutputFidelityVerdict.of(pipeline.copy(processingStage = "crossfade or Automix").fidelityFacts()))
    }

    @Test fun `a 32-bit integer source is not called exact through the float32 decoder`() {
        val pipeline = DesktopAudioPipeline(sourceFormat = DesktopStreamFormat(codec = "flac", bitDepth = 32), outputBytesPerSample = 4, outputIsFloat = true, directOutput = true)
        assertFalse(pipeline.fidelityFacts().encodingExact)
    }

    @Test fun `visual sampling is bounded and reflects gain without editing input`() {
        val original = FloatArray(4096) { .5f }
        val frame = visualFrame(original, original.size, .5f)
        assertEquals(128, frame.wave.size)
        assertEquals(.25f, frame.energy)
        assertTrue(frame.wave.all { it == .25f })
        assertTrue(original.all { it == .5f })
        assertEquals(0f, visualFrame(original, 0, 1f).energy)
    }
}
