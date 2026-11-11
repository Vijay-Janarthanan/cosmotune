package com.cosmotune.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.*

class AcousticAlignmentTest {
    @Test fun detectsPositiveAndNegativePaddingDespiteDifferentLoudness() {
        val random = Random(87)
        val recording = FloatArray(500) { random.nextFloat().let { if (it > .8f) it else .03f } }
        val reference = recording.copyOfRange(100, 300)
        for (shift in listOf(-37, -8, 0, 13, 41)) {
            val copy = FloatArray(300) { recording[it + 50 - shift] * .4f }
            assertEquals(shift * 10L, AcousticAlignment.offset(reference, copy))
        }
    }

    @Test fun silenceAndAmbiguousRepeatingBeatsDoNotInventAnOffset() {
        assertNull(AcousticAlignment.offset(FloatArray(200), FloatArray(300)))
        assertNull(AcousticAlignment.offset(FloatArray(200) { if (it % 10 == 0) 1f else .1f }, FloatArray(300) { if (it % 10 == 0) 1f else .1f }))
    }

    @Test fun envelopeUsesMediaTimestampsAndRejectsHoles() {
        val meter = AcousticAlignment.Envelope()
        meter.add(FloatArray(4800) { .5f }, 4800, 1000.0, 48_000)
        assertTrue(meter.read(1000, 10)!!.all { abs(it - .5f) < .001 })
        assertNull(meter.read(990, 10))
    }

    /**
     * The whole path the two players take: PCM from two differently encoded copies (different
     * rates, chunk sizes and noise, the new one quieter and padded), metered on their own media
     * clocks, then compared at the handover position.
     */
    @Test fun decodedCopiesWithEncoderPaddingAlignThroughTheirEnvelopes() {
        val song = Song(Random(5))
        for (padding in listOf(-310L, -40L, 0L, 120L, 430L)) {
            val old = AcousticAlignment.Envelope()
            val new = AcousticAlignment.Envelope()
            feed(old, song, rate = 44_100, channels = 2, paddingMs = 0, gain = 1f, chunk = 1152, noise = Random(1))
            feed(new, song, rate = 48_000, channels = 2, paddingMs = padding, gain = .35f, chunk = 1024 + 333, noise = Random(2))
            val measured = assertNotNull(AcousticAlignment.alignedOffset(old, new, positionMs = 5_000), "padding $padding")
            assertTrue(abs(measured - padding) <= AcousticAlignment.STEP_MS, "padding $padding measured $measured")
        }
    }

    @Test fun aDifferentRecordingIsNotAligned() {
        val old = AcousticAlignment.Envelope()
        val other = AcousticAlignment.Envelope()
        feed(old, Song(Random(5)), 44_100, 2, 0, 1f, 1152, Random(1))
        feed(other, Song(Random(99)), 44_100, 2, 0, 1f, 1152, Random(2))
        assertNull(AcousticAlignment.alignedOffset(old, other, positionMs = 5_000))
    }

    @Test fun aCopyThatHasNotDecodedFarEnoughGivesNoAnswer() {
        val song = Song(Random(5))
        val old = AcousticAlignment.Envelope()
        val new = AcousticAlignment.Envelope()
        feed(old, song, 44_100, 2, 0, 1f, 1152, Random(1))
        feed(new, song, 44_100, 2, 0, 1f, 1152, Random(2), untilMs = 4_000)
        assertNull(AcousticAlignment.alignedOffset(old, new, positionMs = 5_000))
    }

    @Test fun aSeekBackStartsAgainInsteadOfDoublingTheReplayedBins() {
        val meter = AcousticAlignment.Envelope()
        meter.add(FloatArray(48_000) { .5f }, 48_000, 0.0, 48_000)
        // A seek back to 200 ms and the same audio decoded again.
        meter.add(FloatArray(48_000) { .25f }, 48_000, 200.0, 48_000)
        assertNull(meter.read(0, 10), "audio from before the seek was dropped")
        assertTrue(meter.read(200, 50)!!.all { abs(it - .25f) < .001 })
    }

    @Test fun aForwardJumpLeavesAHoleThatIsRefused() {
        val meter = AcousticAlignment.Envelope()
        meter.add(FloatArray(4_800) { .5f }, 4_800, 0.0, 48_000)
        meter.add(FloatArray(4_800) { .5f }, 4_800, 300.0, 48_000)
        assertNotNull(meter.read(0, 10))
        assertNotNull(meter.read(300, 10))
        assertNull(meter.read(0, 40))
    }

    @Test fun outOfPhaseStereoStillMeasures() {
        val meter = AcousticAlignment.Envelope()
        val frames = 4_410
        val samples = FloatArray(frames * 2) { if (it % 2 == 0) .5f else -.5f }
        meter.add(samples, samples.size, 0.0, 44_100, channels = 2)
        assertTrue(meter.read(0, 10)!!.all { abs(it - .5f) < .001 })
    }

    @Test fun rejectsNonsenseBlocks() {
        val meter = AcousticAlignment.Envelope()
        meter.add(FloatArray(480), 480, Double.NaN, 48_000)
        meter.add(FloatArray(480), 480, -10.0, 48_000)
        meter.add(FloatArray(480), 480, 0.0, 0)
        assertNull(meter.read(0, 1))
        assertNull(meter.read(0, 0))
    }

    @Test fun boundedMemory() {
        val meter = AcousticAlignment.Envelope(capacityBins = 100)
        repeat(30) { meter.add(FloatArray(4_800) { .5f }, 4_800, it * 100.0, 48_000) }
        assertNull(meter.read(0, 10), "oldest bins are evicted")
        assertNotNull(meter.read(2_000, 100))
    }

    @Test fun readsWhileADecoderThreadWrites() {
        val meter = AcousticAlignment.Envelope()
        val writer = Thread { repeat(2_000) { meter.add(FloatArray(960) { .2f }, 960, it * 10.0, 48_000, channels = 2) } }
        writer.start()
        repeat(2_000) { meter.read(1_000, 50) }
        writer.join()
        assertNotNull(meter.read(1_000, 50))
    }

    @Test fun theComparisonWindowNeverReadsBeforeTheMargin() {
        assertEquals(AcousticAlignment.MARGIN_MS, AcousticAlignment.comparisonStartMs(1_000))
        assertEquals(2_400, AcousticAlignment.comparisonStartMs(5_000))
        assertEquals(2_400, AcousticAlignment.comparisonStartMs(5_009))
    }

    @Test fun handoverAlignmentStaysOffUntilRealRenditionsProveIt() {
        assertFalse(AcousticAlignment.applyToUpgrades)
    }

    /** Irregular, decaying hits over a quiet bed: what onsets look like, without any periodicity. */
    private class Song(random: Random) {
        val hits: List<Double> = buildList {
            var t = 50.0
            while (t < 8_000) { add(t); t += 70 + random.nextDouble() * 330 }
        }
        fun level(ms: Double): Float {
            var value = .02
            for (hit in hits) if (ms >= hit && ms - hit < 300) value += exp(-(ms - hit) / 40.0)
            return value.toFloat()
        }
    }

    /** Song time t sounds at copy time t + [paddingMs]. */
    private fun feed(meter: AcousticAlignment.Envelope, song: Song, rate: Int, channels: Int, paddingMs: Long, gain: Float,
        chunk: Int, noise: Random, untilMs: Long = 6_000) {
        val total = (untilMs * rate / 1000).toInt()
        var frame = 0
        while (frame < total) {
            val count = minOf(chunk, total - frame)
            val block = FloatArray(count * channels)
            for (i in 0 until count) {
                val copyMs = (frame + i) * 1000.0 / rate
                val songMs = copyMs - paddingMs
                val level = if (songMs < 0) 0f else song.level(songMs) * gain
                val sample = level * (noise.nextFloat() * 2 - 1)
                for (c in 0 until channels) block[i * channels + c] = sample
            }
            meter.add(block, block.size, frame * 1000.0 / rate, rate, channels)
            frame += count
        }
    }
}
