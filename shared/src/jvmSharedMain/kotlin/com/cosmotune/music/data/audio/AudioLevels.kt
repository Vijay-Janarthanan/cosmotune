package com.cosmotune.music.data.audio

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How loud the music is right now, split into bass, mids and highs, with a flag for a beat: what the Pulse lyric style moves to.
 *
 * Read from the PCM the app already decodes (the desktop's engine, just before its sink; the phone's float DSP stage), never
 * from a microphone, and never changing a sample. Each reading is dated by when it will be *heard* (the moment it was written
 * plus what the output still had queued in front of it), so a frame asks for "now" and gets the levels of the sound coming out
 * of the speaker, not of the sound half a second ahead of it in the buffer.
 */
interface AudioLevels {
    /** Fills [into] with the levels heard at [nowNanos] (the [System.nanoTime] clock). False when nothing is playing through the tap. */
    fun read(nowNanos: Long, into: AudioLevelReading): Boolean

    /** Asks for the analysis to run; it does no work at all while nobody holds one. Close it when the style goes. */
    fun listen(): AutoCloseable
}

/** One reading: each band 0..1 (normalised to the song's own recent loudness), and the last beat's time. Reused, never reallocated. */
class AudioLevelReading {
    var bass = 0f
    var mid = 0f
    var high = 0f

    /** When the last beat was heard ([System.nanoTime] clock), or 0 for none yet. */
    var beatAtNanos = 0L

    /** Whether a beat landed within the last [AudioLevelMeter.BEAT_WINDOW_MS]. */
    var beat = false
}

/** The app's one tap, set by whichever platform plays: the desktop engine, or the phone's audio sink. Null where there is none. */
object AudioLevelsHub {
    @Volatile var source: AudioLevels? = null
}

/**
 * The analysis behind [AudioLevels], shared by both platforms: three biquad filters (RBJ: a 150 Hz low pass, a 1 kHz band pass
 * and a 3.5 kHz high pass) over the mono mix, their energy gathered over [HOP_HZ]-a-second hops, each band normalised to a peak
 * that decays over a few seconds (and to no less than a share of the loudest band's), so a quiet song lights as much as a loud
 * one and an empty band stays dark. A beat is the bass rising well over its own
 * running average, no closer than [MIN_BEAT_GAP_MS] to the last one.
 *
 * Cost: a dozen multiply-adds a frame, done in the writer's own call because copying the block to another thread would cost
 * more than the filters; no allocation after [configure]; nothing at all (an early return) while nobody [listen]s.
 *
 * One writer (the audio thread) and any number of readers (the UI): the readings go into a ring of [RING] slots and the count is
 * published last, so a reader never sees a slot before it has been written. The ring covers seconds, far more than any output
 * holds queued, so a slot a reader is looking at is not rewritten under it.
 */
class AudioLevelMeter : AudioLevels {
    private val listeners = AtomicInteger(0)

    /** Whether anybody wants readings: the writer checks this first and does nothing when not. */
    val active: Boolean get() = listeners.get() > 0

    override fun listen(): AutoCloseable {
        listeners.incrementAndGet()
        return object : AutoCloseable {
            private var open = true
            override fun close() {
                if (open) {
                    open = false
                    listeners.decrementAndGet()
                }
            }
        }
    }

    private var sampleRate = 0
    private var channels = 2
    private var hopFrames = 0
    private val low = Biquad()
    private val band = Biquad()
    private val highPass = Biquad()

    // The hop being gathered.
    private var hopAt = 0
    private var sumLow = 0.0
    private var sumMid = 0.0
    private var sumHigh = 0.0

    // Normalising peaks and the running bass average for the beat.
    private var peakLow = FLOOR
    private var peakMid = FLOOR
    private var peakHigh = FLOOR
    private var averageLow = 0f
    private var lastLow = 0f
    private var envLow = 0f
    private var envMid = 0f
    private var envHigh = 0f
    private var lastBeatNanos = 0L

    private val heardAt = LongArray(RING)
    private val bassAt = FloatArray(RING)
    private val midAt = FloatArray(RING)
    private val highAt = FloatArray(RING)
    private val beatAt = LongArray(RING)

    @Volatile private var written = 0L
    private var lastHeardNanos = Long.MIN_VALUE

    /** Sets the filters for [rate] and [channelCount]. Call before [process] and whenever the format changes. */
    fun configure(rate: Int, channelCount: Int) {
        if (rate <= 0) return
        sampleRate = rate
        channels = channelCount.coerceAtLeast(1)
        hopFrames = (rate / HOP_HZ).coerceAtLeast(1)
        low.lowPass(rate, 150.0, 0.707)
        band.bandPass(rate, 1_000.0, 0.7)
        highPass.highPass(rate, 3_500.0, 0.707)
        reset()
    }

    /** [configure] when [rate] or [channelCount] differ from what the filters are set for; nothing otherwise. */
    fun ensureFormat(rate: Int, channelCount: Int) {
        if (rate != sampleRate || channelCount.coerceAtLeast(1) != channels) configure(rate, channelCount)
    }

    /** Forgets the filters' memory and every reading not yet heard: a seek or a flush, where the queued sound is thrown away. */
    fun reset() {
        low.clear(); band.clear(); highPass.clear()
        hopAt = 0
        sumLow = 0.0; sumMid = 0.0; sumHigh = 0.0
        envLow = 0f; envMid = 0f; envHigh = 0f
        lastLow = 0f
        for (i in 0 until RING) heardAt[i] = Long.MIN_VALUE
        written = 0L
    }

    /**
     * Reads [count] interleaved samples of [samples], the first of which will be heard at [heardAtNanos]. Does not touch the
     * samples. Returns at once while nobody listens or before [configure].
     */
    fun process(samples: FloatArray, count: Int, heardAtNanos: Long) {
        if (!active || sampleRate <= 0) return
        // Sound that will be heard before what was written last: the output was flushed (a seek, a skip), and the readings still
        // waiting for their turn belong to sound that will now never play.
        if (written > 0 && heardAtNanos + FLUSH_SLACK_NANOS < lastHeardNanos) {
            for (i in 0 until RING) heardAt[i] = Long.MIN_VALUE
            written = 0L
            hopAt = 0
            sumLow = 0.0; sumMid = 0.0; sumHigh = 0.0
        }
        val ch = channels
        val frames = minOf(count, samples.size) / ch
        val nanosPerFrame = 1_000_000_000.0 / sampleRate
        var index = 0
        for (frame in 0 until frames) {
            var mono = 0f
            for (c in 0 until ch) mono += samples[index + c]
            index += ch
            mono /= ch
            if (!mono.isFinite()) mono = 0f
            val l = low.step(mono)
            val m = band.step(mono)
            val h = highPass.step(mono)
            sumLow += l * l
            sumMid += m * m
            sumHigh += h * h
            if (++hopAt >= hopFrames) finishHop(heardAtNanos + ((frame + 1) * nanosPerFrame).toLong())
        }
    }

    private fun finishHop(heard: Long) {
        val n = hopAt.coerceAtLeast(1)
        val rmsLow = sqrt(sumLow / n).toFloat()
        val rmsMid = sqrt(sumMid / n).toFloat()
        val rmsHigh = sqrt(sumHigh / n).toFloat()
        hopAt = 0
        sumLow = 0.0; sumMid = 0.0; sumHigh = 0.0

        peakLow = maxOf(rmsLow, peakLow * PEAK_DECAY, FLOOR)
        peakMid = maxOf(rmsMid, peakMid * PEAK_DECAY, FLOOR)
        peakHigh = maxOf(rmsHigh, peakHigh * PEAK_DECAY, FLOOR)
        // Fast up, slower down, so the glow swells with a kick and eases off rather than flickering at the hop rate.
        // Each band against its own peak, but never against less than a share of the loudest band's: a band with nothing in it
        // (the highs of a bass tone) stays dark instead of being lifted to full by its own tiny peak.
        val shared = maxOf(peakLow, peakMid, peakHigh) * CROSS_SHARE
        envLow = follow(envLow, shape(rmsLow / maxOf(peakLow, shared)))
        envMid = follow(envMid, shape(rmsMid / maxOf(peakMid, shared)))
        envHigh = follow(envHigh, shape(rmsHigh / maxOf(peakHigh, shared)))

        // A beat is an onset: the bass well over its running average and rising from the hop before, not a note being held.
        val beat = rmsLow > averageLow * BEAT_RATIO && rmsLow > lastLow * ONSET_RATIO && rmsLow > BEAT_FLOOR &&
            (lastBeatNanos == 0L || heard - lastBeatNanos > MIN_BEAT_GAP_MS * 1_000_000L)
        averageLow += (rmsLow - averageLow) * AVERAGE_RATE
        lastLow = rmsLow
        if (beat) lastBeatNanos = heard

        lastHeardNanos = heard
        val slot = (written % RING).toInt()
        heardAt[slot] = heard
        bassAt[slot] = envLow
        midAt[slot] = envMid
        highAt[slot] = envHigh
        beatAt[slot] = lastBeatNanos
        written++
    }

    override fun read(nowNanos: Long, into: AudioLevelReading): Boolean {
        val count = written
        if (count == 0L) return false
        // The newest slot already heard, walking back from the newest written (which may still be in the output's queue).
        var k = count - 1
        val oldest = maxOf(0L, count - RING + 8)
        while (k >= oldest && heardAt[(k % RING).toInt()] > nowNanos) k--
        if (k < oldest) return false
        val slot = (k % RING).toInt()
        val at = heardAt[slot]
        if (nowNanos - at > STALE_MS * 1_000_000L) return false
        // Between this slot and the next, so a frame between two hops is between their levels too.
        var t = 0f
        var next = slot
        if (k + 1 < count) {
            next = ((k + 1) % RING).toInt()
            val span = heardAt[next] - at
            if (span > 0) t = ((nowNanos - at).toFloat() / span).coerceIn(0f, 1f)
        }
        into.bass = bassAt[slot] + (bassAt[next] - bassAt[slot]) * t
        into.mid = midAt[slot] + (midAt[next] - midAt[slot]) * t
        into.high = highAt[slot] + (highAt[next] - highAt[slot]) * t
        into.beatAtNanos = beatAt[slot]
        into.beat = beatAt[slot] != 0L && nowNanos - beatAt[slot] in 0..BEAT_WINDOW_MS * 1_000_000L
        return true
    }

    private fun follow(current: Float, target: Float): Float =
        if (target > current) current + (target - current) * ATTACK else current + (target - current) * RELEASE

    /** A level as a fraction of its peak, eased so the quieter half of a song still shows. */
    private fun shape(x: Float): Float = sqrt(x.coerceIn(0f, 1f))

    /** A second-order filter (RBJ's cookbook), direct form I, its coefficients normalised by a0. */
    private class Biquad {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun step(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = if (y.isFinite()) y else 0f
            return y1
        }

        fun clear() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

        fun lowPass(rate: Int, hz: Double, q: Double) {
            val (w, alpha) = omega(rate, hz, q)
            val c = cos(w)
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(rate: Int, hz: Double, q: Double) {
            val (w, alpha) = omega(rate, hz, q)
            val c = cos(w)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /** Constant 0 dB peak gain. */
        fun bandPass(rate: Int, hz: Double, q: Double) {
            val (w, alpha) = omega(rate, hz, q)
            val c = cos(w)
            set(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
        }

        private fun omega(rate: Int, hz: Double, q: Double): Pair<Double, Double> {
            val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
            return w to sin(w) / (2 * q)
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
            clear()
        }
    }

    companion object {
        /** How many readings a second. */
        const val HOP_HZ = 40

        /** Readings kept: about six seconds, far more than any output holds queued. */
        const val RING = 256

        /** A reading older than this means the music has stopped (paused, or between songs). */
        const val STALE_MS = 300L

        /** How long after a beat the reading still says [AudioLevelReading.beat]. */
        const val BEAT_WINDOW_MS = 90L

        /** Beats closer than this are one beat (a quarter at about 210 bpm). */
        const val MIN_BEAT_GAP_MS = 280L

        private const val FLUSH_SLACK_NANOS = 60_000_000L
        private const val FLOOR = 1e-4f
        /** The normalising peak halves in about three seconds at [HOP_HZ]. */
        private const val PEAK_DECAY = 0.9942f
        private const val AVERAGE_RATE = 0.04f
        private const val BEAT_RATIO = 1.45f
        private const val ONSET_RATIO = 1.2f
        private const val BEAT_FLOOR = 0.008f
        private const val CROSS_SHARE = 0.3f
        private const val ATTACK = 0.65f
        private const val RELEASE = 0.18f
    }
}
