package com.cosmotune.music.data.audio

import com.cosmotune.lyrics.styles.PulseMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioLevelMeterTest {
    private val rate = 44_100

    /** [seconds] of stereo at [hz] (or silence for 0), interleaved. */
    private fun tone(hz: Double, seconds: Double, amplitude: Float = 0.5f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i ->
            if (hz == 0.0) 0f else (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat()
        }
    }

    private fun meter(): Pair<AudioLevelMeter, AutoCloseable> {
        val meter = AudioLevelMeter()
        meter.configure(rate, 2)
        return meter to meter.listen()
    }

    /** Feeds [samples] as if they were all heard from t = 0, and reads the levels at the end. */
    private fun levelsAfter(meter: AudioLevelMeter, samples: FloatArray): AudioLevelReading {
        meter.process(samples, samples.size, 0L)
        val reading = AudioLevelReading()
        val end = (samples.size / 2) * 1_000_000_000L / rate
        assertTrue(meter.read(end, reading), "a reading is there")
        return reading
    }

    @Test fun bassIsBassAndHighsAreHighs() {
        val (low, l) = meter()
        val deep = levelsAfter(low, tone(60.0, 1.0))
        val (high, h) = meter()
        val bright = levelsAfter(high, tone(8_000.0, 1.0))
        println("60 Hz: bass ${deep.bass} mid ${deep.mid} high ${deep.high}; 8 kHz: bass ${bright.bass} mid ${bright.mid} high ${bright.high}")
        // Normalised to their own peaks, so compare the shares, not the raw levels: a band with nothing in it stays near its floor.
        assertTrue(deep.bass > 0.8f, "a 60 Hz tone fills the bass")
        assertTrue(bright.high > 0.8f, "an 8 kHz tone fills the highs")
        assertTrue(deep.high < 0.3f && deep.mid < 0.6f, "and leaves the others dark")
        assertTrue(bright.bass < 0.3f, "and the other way round")
        l.close(); h.close()
    }

    @Test fun theSamplesAreNeverTouched() {
        val (meter, handle) = meter()
        val samples = tone(440.0, 0.2)
        val before = samples.copyOf()
        meter.process(samples, samples.size, 0L)
        assertContentEquals(before, samples)
        handle.close()
    }

    @Test fun nobodyListeningMeansNoWorkAndNoReadings() {
        val meter = AudioLevelMeter()
        meter.configure(rate, 2)
        assertFalse(meter.active)
        meter.process(tone(60.0, 0.5), (rate * 0.5).toInt() * 2, 0L)
        assertFalse(meter.read(Long.MAX_VALUE / 2, AudioLevelReading()))
        val handle = meter.listen()
        assertTrue(meter.active)
        handle.close()
        handle.close()
        assertFalse(meter.active, "closing twice lets go once")
    }

    @Test fun aReadingIsHeardWhenItsSoundIsNotWhenItWasWritten() {
        val (meter, handle) = meter()
        val lead = 500_000_000L // half a second of output queued in front
        meter.process(tone(60.0, 0.5), (rate * 0.5).toInt() * 2, lead)
        val reading = AudioLevelReading()
        assertFalse(meter.read(lead - 1, reading), "nothing of this has been heard yet")
        assertTrue(meter.read(lead + 200_000_000L, reading))
        assertFalse(meter.read(lead + 2_000_000_000L, reading), "long after the last sound the music has stopped")
        handle.close()
    }

    @Test fun aFlushForgetsTheReadingsOfSoundThatWillNeverPlay() {
        val (meter, handle) = meter()
        meter.process(tone(60.0, 0.5), (rate * 0.5).toInt() * 2, 1_000_000_000L)
        // The next block will be heard straight away: the output was emptied.
        meter.process(tone(8_000.0, 0.1), (rate * 0.1).toInt() * 2, 0L)
        val reading = AudioLevelReading()
        assertFalse(meter.read(1_200_000_000L, reading), "the thrown-away bass is gone")
        assertTrue(meter.read(90_000_000L, reading))
        handle.close()
    }

    @Test fun kicksAreBeatsAndASteadyToneIsNot() {
        val (meter, handle) = meter()
        // Four kicks, half a second apart: 60 ms of 55 Hz, then quiet.
        val beat = FloatArray(rate) // half a second, stereo
        for (i in 0 until beat.size / 2) {
            val s = if (i < rate * 0.06) (0.8 * sin(2 * PI * 55 * i / rate)).toFloat() else 0f
            beat[2 * i] = s; beat[2 * i + 1] = s
        }
        var beats = 0
        var at = 0L
        val reading = AudioLevelReading()
        var lastBeat = 0L
        repeat(4) {
            meter.process(beat, beat.size, at)
            for (ms in 0 until 500 step 10) {
                if (meter.read(at + ms * 1_000_000L, reading) && reading.beatAtNanos != lastBeat && reading.beatAtNanos != 0L) {
                    beats++
                    lastBeat = reading.beatAtNanos
                }
            }
            at += 500_000_000L
        }
        assertTrue(beats >= 3, "the kicks are found: $beats")
        handle.close()

        val (steady, other) = meter()
        steady.process(tone(55.0, 2.0), (rate * 2.0).toInt() * 2, 0L)
        val r = AudioLevelReading()
        assertTrue(steady.read(1_900_000_000L, r))
        assertTrue(r.beatAtNanos < 600_000_000L, "a held tone is at most one beat, as it starts")
        other.close()
    }

    @Test fun silenceAndNonsenseNeverComeOutAsNaN() {
        val (meter, handle) = meter()
        val reading = levelsAfter(meter, FloatArray(8_820) { if (it % 7 == 0) Float.NaN else 0f })
        assertTrue(reading.bass.isFinite() && reading.mid.isFinite() && reading.high.isFinite())
        handle.close()
    }

    @Test fun pulseFallsBackToASlowBreathOnNoTempo() {
        assertEquals(0f, PulseMotion.breath(0), 0.0001f)
        assertEquals(1f, PulseMotion.breath(PulseMotion.BREATH_MS / 2), 0.0001f)
        assertEquals(0f, PulseMotion.kick(-1f))
        assertEquals(1f, PulseMotion.kick(0f))
        assertTrue(PulseMotion.kick(500f) < 0.05f)
        assertTrue(PulseMotion.lineScale(1f) <= 1.03f, "a kick never moves the line far")
        for (i in 0 until 14) assertTrue(PulseMotion.bar(i, 14, 1f, 0.5f, 0.2f, 1234) in 0f..1f)
    }
}
