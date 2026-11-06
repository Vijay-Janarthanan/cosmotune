// The crossfeed (bs2b) and the spherical-head renderer are ported from Mochy Music's eq/audio/SpatialAudioProcessor.kt
// (https://github.com/realidkroo/Mochy-Music, GPL-3.0, revision 8dd8f3b0), copyright the Mochy Music authors, and adapted for
// Cosmotune under the same licence (float blocks in place, presets, no PCM16 conversion of its own). See NOTICE.md.

package com.cosmotune.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** How the headphone effect renders a stereo mix. */
enum class SpatialMode { CROSSFEED, SPEAKERS, MONO }

/**
 * The spatial-audio presets both apps offer. Crossfeed uses Bauer's stereophonic-to-binaural (bs2b) filter at its three
 * classic settings; the speaker presets place two virtual speakers in front of a spherical head (Brown and Duda's model)
 * at the given angle; mono folds the mix to both ears for one earbud or hearing in one ear.
 */
enum class SpatialPreset(
    val label: String,
    val detail: String,
    val mode: SpatialMode,
    val cutoffHz: Float = 700f,
    val feedDb: Float = 4.5f,
    val speakerAngle: Float = 30f,
) {
    CROSSFEED_GENTLE("Crossfeed", "bs2b's default (700 Hz, 4.5 dB): a little of each side in the other ear", SpatialMode.CROSSFEED),
    CROSSFEED_CHU_MOY("Crossfeed: Chu Moy", "700 Hz, 6 dB: closer to listening on speakers", SpatialMode.CROSSFEED, feedDb = 6f),
    CROSSFEED_MEIER("Crossfeed: Jan Meier", "650 Hz, 9.5 dB: the strongest blend", SpatialMode.CROSSFEED, cutoffHz = 650f, feedDb = 9.5f),
    SPEAKERS_NEAR("Virtual speakers", "Two speakers 30° either side of straight ahead", SpatialMode.SPEAKERS, speakerAngle = 30f),
    SPEAKERS_WIDE("Virtual speakers: wide", "Two speakers 45° either side", SpatialMode.SPEAKERS, speakerAngle = 45f),
    MONO("Mono", "Both ears hear the whole mix", SpatialMode.MONO),
    ;

    companion object {
        val DEFAULT = CROSSFEED_GENTLE
        fun named(name: String?): SpatialPreset = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Headphone spatial audio on interleaved float blocks, in place. Stereo goes through the [SpatialPreset]'s crossfeed,
 * virtual speakers or mono fold; with [renderSurround], a 3- to 8-channel mix is rendered to two ears from the standard
 * speaker positions and written to the first two channels, the rest silenced, so a fold-down to stereo after this passes
 * the binaural result through. Switching on, off or between presets glides over [TOGGLE_SECONDS] rather than clicking.
 */
class SpatialDsp(private val sampleRate: Int, private val channels: Int) {

    private var amount = 0f
    private var crossfeed: Crossfeed? = null
    private var speakers: HeadRenderer? = null
    private var speakerAngle = Float.NaN
    private var surround: HeadRenderer? = null
    private val frame = FloatArray(maxOf(channels, 2))
    private val step = 1f / (sampleRate.coerceAtLeast(1) * TOGGLE_SECONDS)

    /** Whether there is anything to render for this channel layout. */
    val applies: Boolean get() = channels == 2 || channels in 3..8

    fun process(samples: FloatArray, count: Int, enabled: Boolean, preset: SpatialPreset, renderSurround: Boolean) {
        if (channels in 3..8) {
            if (enabled && renderSurround) renderSurround(samples, count)
            return
        }
        if (channels != 2) return
        val target = if (enabled) 1f else 0f
        if (target == 0f && amount == 0f) return
        when (preset.mode) {
            SpatialMode.CROSSFEED -> {
                val cf = crossfeed?.takeIf { it.matches(preset.cutoffHz, preset.feedDb) }
                    ?: Crossfeed(sampleRate, preset.cutoffHz, preset.feedDb, crossfeed).also { crossfeed = it }
                var i = 0
                while (i + 1 < count) {
                    val l = samples[i]
                    val r = samples[i + 1]
                    amount = glide(amount, target)
                    cf.process(l, r)
                    samples[i] = l + (cf.left - l) * amount
                    samples[i + 1] = r + (cf.right - r) * amount
                    i += 2
                }
            }
            SpatialMode.SPEAKERS -> {
                val angle = preset.speakerAngle.coerceIn(5f, 90f)
                val renderer = speakers?.takeIf { speakerAngle == angle }
                    ?: HeadRenderer(sampleRate, floatArrayOf(-angle, angle)).also { speakers = it; speakerAngle = angle }
                var i = 0
                while (i + 1 < count) {
                    val l = samples[i]
                    val r = samples[i + 1]
                    amount = glide(amount, target)
                    frame[0] = l
                    frame[1] = r
                    renderer.render(frame)
                    samples[i] = l + (renderer.left * SPEAKER_GAIN - l) * amount
                    samples[i + 1] = r + (renderer.right * SPEAKER_GAIN - r) * amount
                    i += 2
                }
            }
            SpatialMode.MONO -> {
                var i = 0
                while (i + 1 < count) {
                    val l = samples[i]
                    val r = samples[i + 1]
                    amount = glide(amount, target)
                    val m = (l + r) * 0.5f
                    samples[i] = l + (m - l) * amount
                    samples[i + 1] = r + (m - r) * amount
                    i += 2
                }
            }
        }
    }

    private fun renderSurround(samples: FloatArray, count: Int) {
        val renderer = surround ?: HeadRenderer(sampleRate, surroundAzimuths(channels)).also { surround = it }
        var at = 0
        while (at + channels <= count) {
            for (c in 0 until channels) frame[c] = samples[at + c]
            renderer.render(frame)
            samples[at] = renderer.left * SURROUND_GAIN
            samples[at + 1] = renderer.right * SURROUND_GAIN
            for (c in 2 until channels) samples[at + c] = 0f
            at += channels
        }
    }

    fun reset() {
        crossfeed?.clear()
        speakers?.clear()
        surround?.clear()
    }

    private fun glide(value: Float, target: Float): Float =
        if (target > value) minOf(target, value + step) else maxOf(target, value - step)

    /** bs2b, with its state carried over when the settings move so the sound doesn't jump. */
    internal class Crossfeed(sampleRate: Int, private val cutoff: Float, private val feed: Float, previous: Crossfeed?) {
        private val a0Lo: Float
        private val b1Lo: Float
        private val a0Hi: Float
        private val a1Hi: Float
        private val b1Hi: Float
        private val gain: Float
        private var loL: Float = previous?.loL ?: 0f
        private var loR: Float = previous?.loR ?: 0f
        private var hiL: Float = previous?.hiL ?: 0f
        private var hiR: Float = previous?.hiR ?: 0f
        private var asisL: Float = previous?.asisL ?: 0f
        private var asisR: Float = previous?.asisR ?: 0f
        var left = 0f
            private set
        var right = 0f
            private set

        init {
            val fc = cutoff.coerceIn(300f, 2000f).toDouble()
            val fd = feed.coerceIn(1f, 15f).toDouble()
            val gbLo = fd * -5.0 / 6.0 - 3.0
            val gbHi = fd / 6.0 - 3.0
            val gLo = 10.0.pow(gbLo / 20.0)
            val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
            val fcHi = fc * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)
            val xLo = exp(-2.0 * PI * fc / sampleRate)
            b1Lo = xLo.toFloat()
            a0Lo = (gLo * (1.0 - xLo)).toFloat()
            val xHi = exp(-2.0 * PI * fcHi / sampleRate)
            b1Hi = xHi.toFloat()
            a0Hi = (1.0 - gHi * (1.0 - xHi)).toFloat()
            a1Hi = (-xHi).toFloat()
            gain = (1.0 / (1.0 - gHi + gLo)).toFloat()
        }

        fun matches(cutoff: Float, feed: Float) = this.cutoff == cutoff && this.feed == feed

        fun process(l: Float, r: Float) {
            loL = a0Lo * l + b1Lo * loL
            loR = a0Lo * r + b1Lo * loR
            hiL = a0Hi * l + a1Hi * asisL + b1Hi * hiL
            hiR = a0Hi * r + a1Hi * asisR + b1Hi * hiR
            asisL = l
            asisR = r
            left = (hiL + loR) * gain
            right = (hiR + loL) * gain
        }

        fun clear() {
            loL = 0f; loR = 0f; hiL = 0f; hiR = 0f; asisL = 0f; asisR = 0f
        }
    }

    /**
     * Brown and Duda's spherical head: per source and ear a Woodworth time delay and a first-order head-shadow shelf.
     * Azimuths in degrees (0 ahead, positive right); NaN marks an LFE channel, sent to both ears without direction.
     */
    internal class HeadRenderer(sampleRate: Int, private val azimuths: FloatArray) {
        private val n = azimuths.size
        private val line = Array(n) { FloatArray(DELAY_SIZE) }
        private var write = 0
        private val delay = FloatArray(n * 2)
        private val b0 = FloatArray(n * 2)
        private val b1 = FloatArray(n * 2)
        private val a1 = FloatArray(n * 2)
        private val x1 = FloatArray(n * 2)
        private val y1 = FloatArray(n * 2)
        private val weight = FloatArray(n)
        var left = 0f
            private set
        var right = 0f
            private set

        init {
            val w0 = SPEED_OF_SOUND / HEAD_RADIUS
            val k = 2.0 * sampleRate
            val raw = DoubleArray(n * 2)
            for (s in 0 until n) {
                val az = azimuths[s]
                weight[s] = when {
                    az.isNaN() -> 0.5f
                    abs(az) < 1f -> 0.71f
                    else -> 1f
                }
                for (ear in 0..1) {
                    val i = s * 2 + ear
                    if (az.isNaN()) {
                        b0[i] = 1f; b1[i] = 0f; a1[i] = 0f
                        continue
                    }
                    val earAz = if (ear == 0) -90.0 else 90.0
                    val theta = Math.toRadians(abs(wrap(az - earAz)))
                    val alpha = (1 + ALPHA_MIN / 2) + (1 - ALPHA_MIN / 2) * cos(theta / THETA_MIN * PI)
                    val norm = 2 * w0 + k
                    b0[i] = ((2 * w0 + alpha * k) / norm).toFloat()
                    b1[i] = ((2 * w0 - alpha * k) / norm).toFloat()
                    a1[i] = ((2 * w0 - k) / norm).toFloat()
                    raw[i] = if (theta < PI / 2) -HEAD_RADIUS / SPEED_OF_SOUND * cos(theta)
                    else HEAD_RADIUS / SPEED_OF_SOUND * (theta - PI / 2)
                }
            }
            val earliest = raw.minOrNull() ?: 0.0
            for (i in 0 until n * 2) {
                delay[i] = if (azimuths[i / 2].isNaN()) 0f else ((raw[i] - earliest) * sampleRate).toFloat()
                    .coerceIn(0f, (DELAY_SIZE - 2).toFloat())
            }
        }

        /** Delay, in samples, of [source] at [ear] (0 left, 1 right); for tests. */
        fun delayOf(source: Int, ear: Int): Float = delay[source * 2 + ear]

        fun render(frame: FloatArray) {
            var l = 0f
            var r = 0f
            for (s in 0 until n) {
                line[s][write] = frame[s]
                for (ear in 0..1) {
                    val i = s * 2 + ear
                    val d = delay[i]
                    val whole = d.toInt()
                    val frac = d - whole
                    var p0 = write - whole
                    if (p0 < 0) p0 += DELAY_SIZE
                    val p1 = if (p0 == 0) DELAY_SIZE - 1 else p0 - 1
                    val x = line[s][p0] + (line[s][p1] - line[s][p0]) * frac
                    val y = b0[i] * x + b1[i] * x1[i] - a1[i] * y1[i]
                    x1[i] = x
                    y1[i] = y
                    if (ear == 0) l += y * weight[s] else r += y * weight[s]
                }
            }
            write = if (write + 1 == DELAY_SIZE) 0 else write + 1
            left = l
            right = r
        }

        fun clear() {
            line.forEach { it.fill(0f) }
            x1.fill(0f)
            y1.fill(0f)
        }

        private fun wrap(deg: Double): Double {
            var d = deg % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return d
        }

        private companion object {
            const val DELAY_SIZE = 64
            const val HEAD_RADIUS = 0.0875
            const val SPEED_OF_SOUND = 343.0
            const val ALPHA_MIN = 0.1
            const val THETA_MIN = 150.0 * PI / 180.0
        }
    }

    companion object {
        private const val TOGGLE_SECONDS = 0.15f
        private const val SPEAKER_GAIN = 0.6f
        private const val SURROUND_GAIN = 0.45f

        /** The usual channel order: FL FR FC LFE then the rear or side pair (fewer channels drop from the end). */
        fun surroundAzimuths(channels: Int): FloatArray = when (channels) {
            3 -> floatArrayOf(-30f, 30f, 0f)
            4 -> floatArrayOf(-30f, 30f, -110f, 110f)
            5 -> floatArrayOf(-30f, 30f, 0f, -110f, 110f)
            6 -> floatArrayOf(-30f, 30f, 0f, Float.NaN, -110f, 110f)
            7 -> floatArrayOf(-30f, 30f, 0f, Float.NaN, 180f, -90f, 90f)
            else -> floatArrayOf(-30f, 30f, 0f, Float.NaN, -150f, 150f, -90f, 90f)
        }
    }
}
