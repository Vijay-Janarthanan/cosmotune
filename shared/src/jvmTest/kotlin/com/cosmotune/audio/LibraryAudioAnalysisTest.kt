package com.cosmotune.audio

import com.cosmotune.listening.FileLibraryAccess
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

class LibraryAudioAnalysisTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun dbfs(db: Double) = 10.0.pow(db / 20.0)

    /** Interleaved sine sections: (seconds, amplitude). */
    private fun tone(rate: Int, channels: Int, vararg sections: Pair<Double, Double>, hz: Double = 1000.0): FloatArray {
        val frames = sections.sumOf { (it.first * rate).toInt() }
        val out = FloatArray(frames * channels)
        var frame = 0
        for ((seconds, amplitude) in sections) repeat((seconds * rate).toInt()) {
            val value = (amplitude * sin(2 * PI * hz * frame / rate)).toFloat()
            for (c in 0 until channels) out[frame * channels + c] = value
            frame++
        }
        return out
    }

    private fun measure(rate: Int, channels: Int, samples: FloatArray): LoudnessMeter =
        LoudnessMeter(rate, channels).apply {
            // Odd-sized blocks, as decoders deliver them.
            var at = 0
            while (at < samples.size) { val n = minOf(samples.size - at, 3001 * channels); add(samples.copyOfRange(at, at + n), n); at += n }
        }

    @Test fun stereoSineMatchesTheEbuReferenceAtBothCommonRates() {
        for (rate in listOf(48_000, 44_100)) {
            val lufs = assertNotNull(measure(rate, 2, tone(rate, 2, 20.0 to dbfs(-23.0))).integratedLufs())
            assertEquals(-23.0, lufs, 0.1, "rate $rate")
        }
        // A mono signal carries half the power of the same signal on two channels.
        assertEquals(-26.0, measure(48_000, 1, tone(48_000, 1, 10.0 to dbfs(-23.0))).integratedLufs()!!, 0.1)
    }

    @Test fun absoluteAndRelativeGatesIgnoreSilenceAndQuietPassages() {
        val rate = 48_000
        assertEquals(-23.0, measure(rate, 2, tone(rate, 2, 4.0 to dbfs(-36.0), 24.0 to dbfs(-23.0), 4.0 to dbfs(-36.0))).integratedLufs()!!, 0.2)
        assertEquals(-20.0, measure(rate, 2, tone(rate, 2, 10.0 to dbfs(-20.0), 10.0 to 0.0)).integratedLufs()!!, 0.1)
        assertNull(measure(rate, 2, FloatArray(rate * 4)).integratedLufs())
        assertNull(measure(rate, 2, tone(rate, 2, 5.0 to dbfs(-80.0))).integratedLufs())
    }

    @Test fun lowFrequencyEffectsChannelIsExcludedFromSurroundLoudness() {
        val rate = 48_000
        val frames = rate * 5
        val front = FloatArray(frames * 6)
        val lfe = FloatArray(frames * 6)
        for (f in 0 until frames) {
            val v = (dbfs(-23.0) * sin(2 * PI * 1000.0 * f / rate)).toFloat()
            front[f * 6] = v; front[f * 6 + 1] = v
            lfe[f * 6] = v; lfe[f * 6 + 1] = v; lfe[f * 6 + 3] = (0.5 * sin(2 * PI * 1000.0 * f / rate)).toFloat()
        }
        assertEquals(measure(rate, 6, front).integratedLufs()!!, measure(rate, 6, lfe).integratedLufs()!!, 0.01)
    }

    @Test fun replayGainAndPlaybackFigureNeverBoostPeaksPastFullScale() {
        val quiet = LibraryAudioAnalysis("a", "/a", integratedLufs = -23.0, samplePeak = dbfs(-20.0))
        assertEquals(5.0, quiet.replayGainDb!!, 1e-9)
        assertEquals(-9.0, quiet.playbackLoudnessDb!!, 1e-9) // 9 dB boost wanted; the normalizer still limits boosts to 3 dB
        val peaky = LibraryAudioAnalysis("b", "/b", integratedLufs = -20.0, samplePeak = 0.99)
        val limited = peaky.playbackLoudnessDb!!
        assertTrue(-limited + 20 * kotlin.math.log10(0.99) <= -0.0999, "boost must keep the peak below full scale")
        val loud = LibraryAudioAnalysis("c", "/c", integratedLufs = -8.0, samplePeak = 1.0)
        assertEquals(6.0, loud.playbackLoudnessDb!!, 1e-9)
        assertNull(LibraryAudioAnalysis("d", "/d").playbackLoudnessDb)
    }

    @Test fun measuredGainIsAppliedBypassedAndClippingIsPrevented() {
        val rate = 48_000
        val loud = tone(rate, 2, 2.0 to 0.8)
        val analysis = LibraryAudioAnalysis("x", "/x", integratedLufs = measure(rate, 2, loud).integratedLufs(), samplePeak = 0.8)
        val attenuated = loud.copyOf()
        LoudnessNormalizer().process(attenuated, attenuated.size, 2, rate, analysis.playbackLoudnessDb, true)
        val after = measure(rate, 2, attenuated).integratedLufs()!!
        assertEquals(LibraryAudioAnalysis.PLAYBACK_REFERENCE_LUFS, after, 0.3)

        val bypass = loud.copyOf()
        LoudnessNormalizer().process(bypass, bypass.size, 2, rate, analysis.playbackLoudnessDb, false)
        assertContentEquals(loud, bypass)

        val quiet = tone(rate, 2, 2.0 to 0.97, hz = 50.0)
        val boosted = quiet.copyOf()
        LoudnessNormalizer().process(boosted, boosted.size, 2, rate, -30.0, true)
        assertTrue(boosted.all { abs(it) <= 0.98501f })
    }

    @Test fun spectralCutoffSeparatesBandLimitedAndFullBandContent() {
        val rate = 44_100
        val random = Random(4)
        val full = FloatArray(rate * 6 * 2) { (random.nextDouble(-0.3, 0.3)).toFloat() }
        val tones = (0 until 300).map { 100.0 + it * (15_900.0 / 299) to random.nextDouble(0.0, 2 * PI) }
        val limited = FloatArray(rate * 6 * 2)
        for (f in 0 until rate * 6) {
            var v = 0.0
            for ((hz, phase) in tones) v += sin(2 * PI * hz * f / rate + phase)
            limited[f * 2] = (v * 0.004).toFloat(); limited[f * 2 + 1] = limited[f * 2]
        }
        fun cutoff(samples: FloatArray) = SpectralCutoffMeter(rate, 2).apply { add(samples, samples.size) }.cutoffHz()
        assertTrue(assertNotNull(cutoff(full)) >= 21_000)
        val band = assertNotNull(cutoff(limited))
        assertTrue(band in 15_500..16_700, "cutoff $band")
        assertNull(cutoff(FloatArray(rate * 4)))
        assertTrue(LibraryAudioAnalysis("a", "/a", codec = "flac", sampleRate = rate, cutoffHz = band).possibleLossySource)
        assertFalse(LibraryAudioAnalysis("a", "/a", codec = "mp3", sampleRate = rate, cutoffHz = band).possibleLossySource)
        assertFalse(LibraryAudioAnalysis("a", "/a", codec = "flac", sampleRate = rate, cutoffHz = cutoff(full)).possibleLossySource)
    }

    private fun wav(file: File, rate: Int, channels: Int, samples: FloatArray, bits: Int = 16) {
        val width = bits / 8
        val data = ByteBuffer.allocate(samples.size * width).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { v ->
            when (bits) {
                16 -> data.putShort((v * 32767).toInt().toShort())
                24 -> { val i = (v * 8388607).toInt(); data.put(i.toByte()); data.put((i shr 8).toByte()); data.put((i shr 16).toByte()) }
                else -> data.putFloat(v)
            }
        }
        val header = ByteBuffer.allocate(44 + 10).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + 10 + data.capacity()).put("WAVE".toByteArray())
        header.put("LIST".toByteArray()).putInt(2).put(byteArrayOf(0, 0)) // an unrelated chunk to skip
        header.put("fmt ".toByteArray()).putInt(16).putShort((if (bits == 32) 3 else 1).toShort()).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * width).putShort((channels * width).toShort()).putShort(bits.toShort())
        header.put("data".toByteArray()).putInt(data.capacity())
        file.writeBytes(header.array() + data.array())
    }

    @Test fun libraryScanDecodesStoresReusesAndPublishesForPlayback() = runBlocking<Unit> {
        val rate = 44_100
        val first = temporary.newFile("one.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-23.0))) }
        val second = temporary.newFile("two.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-14.0)), bits = 24) }
        val notWav = temporary.newFile("three.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(256)) }
        val storeFile = File(temporary.root, "analysis/cosmotune-library-analysis.json")
        val store = LibraryAnalysisStore(storeFile)
        val songs = listOf(Song("local:one", "One", "A", null, localPath = first.path), Song("local:two", "Two", "B", null, localPath = second.path),
            Song("local:three", "Three", "C", null, localPath = notWav.path), Song("stream", "Online", "D", null))
        var progressCalls = 0
        val result = analyseLocalLibrary(songs, FileLibraryAccess, WavPcmDecoder, store, now = { 1000L }) { progressCalls++ }
        assertEquals(2, result.measured); assertEquals(1, result.unsupported); assertEquals(0, result.failed); assertEquals(3, progressCalls)
        val one = result.entries.single { it.id == "local:one" }
        assertEquals(-23.0, one.integratedLufs!!, 0.15)
        assertEquals(16, one.bitsPerSample); assertEquals(rate, one.sampleRate); assertEquals("PCM", one.codec); assertTrue(one.lossless)
        assertEquals(24, result.entries.single { it.id == "local:two" }.bitsPerSample)
        assertEquals(-14.0, result.entries.single { it.id == "local:two" }.integratedLufs!!, 0.15)
        assertEquals(-9.0, LibraryLoudness.loudnessDbFor("local:one")!!, 0.15)
        assertNull(LibraryLoudness.loudnessDbFor("stream"))
        assertFalse(storeFile.readText().contains("://"))

        val again = analyseLocalLibrary(songs, FileLibraryAccess, WavPcmDecoder, store)
        assertEquals(0, again.measured); assertEquals(2, again.reused)

        wav(first, rate, 2, tone(rate, 2, 8.0 to dbfs(-30.0)))
        first.setLastModified(first.lastModified() + 5000)
        val changed = analyseLocalLibrary(songs, FileLibraryAccess, WavPcmDecoder, store)
        assertEquals(1, changed.measured)
        assertEquals(-30.0, changed.entries.single { it.id == "local:one" }.integratedLufs!!, 0.15)

        LibraryLoudness.publish(emptyList())
        LibraryLoudness.attach(storeFile)
        val deadline = System.currentTimeMillis() + 5000
        while (LibraryLoudness.loudnessDbFor("local:two") == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0.0, LibraryLoudness.loudnessDbFor("local:two")!!, 0.15)
    }

    @Test fun cancelledScanKeepsEarlierResultsAndDamagedStoreIsReported() = runBlocking<Unit> {
        val storeFile = temporary.newFile("bad.json").apply { writeText("{not json") }
        assertFails { LibraryAnalysisStore(storeFile).read() }
        val rate = 8_000
        val file = temporary.newFile("long.wav").also { wav(it, rate, 1, tone(rate, 1, 30.0 to 0.1)) }
        val store = LibraryAnalysisStore(File(temporary.root, "ok.json"))
        val bounded = analyseLocalLibrary(listOf(Song("l", "L", "A", null, localPath = file.path)), FileLibraryAccess, WavPcmDecoder, store, maxSecondsPerTrack = 5)
        assertTrue(bounded.entries.single().durationSeconds in 5.0..5.6)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            analyseLocalLibrary(listOf(Song("m", "M", "A", null, localPath = file.path)), FileLibraryAccess,
                LocalPcmDecoder { s, f -> object : LocalPcmStream by WavPcmDecoder.open(s, f) {
                    override fun read(): FloatArray? = throw kotlinx.coroutines.CancellationException("stop")
                } }, store)
        }
        assertEquals(setOf("l"), store.read().values.map { it.id }.toSet())
    }

    @Test fun albumGainPoolsTheAlbumsTracksAndLeavesOtherAlbumsAlone() = runBlocking<Unit> {
        val rate = 44_100
        // Two tracks of one album, 8 s at -23 LUFS and 8 s at -13 LUFS; a third on another album.
        val quiet = temporary.newFile("a1.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-23.0))) }
        val loud = temporary.newFile("a2.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-13.0))) }
        val other = temporary.newFile("b1.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-30.0))) }
        val single = temporary.newFile("c1.wav").also { wav(it, rate, 2, tone(rate, 2, 8.0 to dbfs(-20.0))) }
        val store = LibraryAnalysisStore(File(temporary.root, "albums.json"))
        val songs = listOf(
            Song("a1", "Quiet", "Band", null, albumName = "Same Album", localPath = quiet.path),
            Song("a2", "Loud", "band", null, albumName = "same album", localPath = loud.path),
            Song("b1", "Alone", "Band", null, albumName = "Another", localPath = other.path),
            Song("c1", "No album", "Band", null, localPath = single.path),
        )
        val entries = analyseLocalLibrary(songs, FileLibraryAccess, WavPcmDecoder, store).entries.associateBy { it.id }
        // Energy mean of -23 and -13 LUFS over equal durations is about -15.6 LUFS: album gain about -2.4 dB.
        val expected = -18.0 - 10 * kotlin.math.log10((10.0.pow(-2.3) + 10.0.pow(-1.3)) / 2)
        assertEquals(expected, entries.getValue("a1").albumGainDb!!, 0.2)
        assertEquals(entries.getValue("a1").albumGainDb, entries.getValue("a2").albumGainDb)
        assertEquals(entries.getValue("a2").samplePeak, entries.getValue("a1").albumPeak!!, 1e-9) // the louder track's peak
        assertEquals(entries.getValue("b1").replayGainDb!!, entries.getValue("b1").albumGainDb!!, 0.011) // an album of one
        // A track with no album gets no album gain; the stored figures survive a reload.
        assertNull(entries.getValue("c1").albumGainDb)
        assertEquals(expected, store.read().values.first { it.id == "a2" }.albumGainDb!!, 0.2)
        assertNull(AlbumGain.apply(listOf(LibraryAudioAnalysis("x", "x", integratedLufs = -20.0, durationSeconds = 3.0))).single().albumGainDb)
    }
}
