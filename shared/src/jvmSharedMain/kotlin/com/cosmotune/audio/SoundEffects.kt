package com.cosmotune.audio

import com.cosmotune.music.playback.PeakLimiter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The sound effects the listener can put on everything that plays, one at a time, as Settings > Sound > Sound effects offers
 * them on both apps. Ideas from SimpMusic, Namida and Mochy Music; the code is Cosmotune's own (the reverb follows the
 * public-domain Freeverb design by Jezar at Dreampoint).
 */
enum class SoundEffect(val label: String, val detail: String) {
    NONE("None", "Songs play as they are"),
    EIGHT_D("8D", "The song circles slowly around your head. Best on headphones"),
    ECHO("Echo", "Each sound comes back softly a moment later, from side to side"),
    ROOM("Room", "As if played in a small room"),
    HALL("Concert hall", "The long, open sound of a large hall"),
    CHORUS("Chorus", "A thicker, shimmering sound, as if several played together"),
    VOCAL_REMOVER("Vocal remover", "Takes out what is sung in the middle, for singing along. Works best on studio mixes"),
    NIGHT("Night mode", "Brings up quiet parts and holds back loud ones, for listening at low volume"),
    ;

    companion object {
        fun named(name: String?): SoundEffect = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * Plays [SoundEffect]s on interleaved stereo float blocks, in place, then the volume boost. Switching effect fades the old
 * one out and the new one in over [FADE_SECONDS], so nothing clicks; with no effect and no boost a block is left untouched.
 * Audio thread only.
 */
class SoundEffectsDsp(private val sampleRate: Int) {

    private var active = SoundEffect.NONE
    private var amount = 0f
    private val step = 1f / (sampleRate.coerceAtLeast(1) * FADE_SECONDS)
    private var boost = 1f
    private val limiter = PeakLimiter().also { it.configure(sampleRate) }

    private var eight: EightD? = null
    private var echo: Echo? = null
    private var reverb: Reverb? = null
    private var reverbHall = false
    private var chorus: Chorus? = null
    private var vocals: VocalRemover? = null
    private var night: Compressor? = null

    /**
     * Renders [count] interleaved stereo samples with [effect] mixed in at [strength] (0..1), then raises the level by
     * [boostGain] (1 or more) under a peak limiter, so a boost never clips.
     */
    fun process(samples: FloatArray, count: Int, effect: SoundEffect, strength: Float, boostGain: Float = 1f) {
        val wet = strength.coerceIn(0f, 1f)
        val wantedBoost = boostGain.coerceIn(1f, MAX_BOOST)
        if (effect == SoundEffect.NONE && amount == 0f && wantedBoost == 1f && boost == 1f) return
        var i = 0
        while (i + 1 < count) {
            val target = if (effect == active && effect != SoundEffect.NONE) 1f else 0f
            amount = if (target > amount) minOf(target, amount + step) else maxOf(target, amount - step)
            if (amount == 0f && effect != active) {
                active = effect
                clearStates()
            }
            var l = samples[i]
            var r = samples[i + 1]
            if (amount > 0f && render(l, r)) {
                val mix = amount * wet
                l += (fxLeft - l) * mix
                r += (fxRight - r) * mix
            }
            boost += (wantedBoost - boost) * BOOST_GLIDE
            if (abs(boost - wantedBoost) < 1e-4f) boost = wantedBoost
            if (boost != 1f || active == SoundEffect.NIGHT) {
                l *= boost
                r *= boost
                val g = limiter.gainFor(max(abs(l), abs(r)))
                l *= g
                r *= g
            }
            samples[i] = l
            samples[i + 1] = r
            i += 2
        }
    }

    private var fxLeft = 0f
    private var fxRight = 0f

    /** Runs [active] on one frame into [fxLeft] and [fxRight]; false when there is nothing to run. */
    private fun render(l: Float, r: Float): Boolean {
        when (active) {
            SoundEffect.NONE -> return false
            SoundEffect.EIGHT_D -> (eight ?: EightD(sampleRate).also { eight = it }).let { it.process(l, r); fxLeft = it.left; fxRight = it.right }
            SoundEffect.ECHO -> (echo ?: Echo(sampleRate).also { echo = it }).let { it.process(l, r); fxLeft = it.left; fxRight = it.right }
            SoundEffect.ROOM, SoundEffect.HALL -> {
                val hall = active == SoundEffect.HALL
                val rv = reverb?.takeIf { reverbHall == hall } ?: Reverb(sampleRate, hall).also { reverb = it; reverbHall = hall }
                rv.process(l, r)
                fxLeft = rv.left
                fxRight = rv.right
            }
            SoundEffect.CHORUS -> (chorus ?: Chorus(sampleRate).also { chorus = it }).let { it.process(l, r); fxLeft = it.left; fxRight = it.right }
            SoundEffect.VOCAL_REMOVER -> (vocals ?: VocalRemover(sampleRate).also { vocals = it }).let { it.process(l, r); fxLeft = it.left; fxRight = it.right }
            SoundEffect.NIGHT -> (night ?: Compressor(sampleRate).also { night = it }).let { it.process(l, r); fxLeft = it.left; fxRight = it.right }
        }
        return true
    }

    /** For a seek or a new stream: tails and delay lines are dropped, the chosen effect stays. */
    fun reset() {
        clearStates()
        limiter.reset()
    }

    private fun clearStates() {
        eight = null
        echo = null
        reverb = null
        chorus = null
        vocals = null
        night = null
    }

    companion object {
        const val FADE_SECONDS = 0.15f

        /** The most the boost raises the level: twice as loud in amplitude, +6 dB. */
        const val MAX_BOOST = 2f
        private const val BOOST_GLIDE = 0.0005f
    }
}

/** The mix pans around the head once every [PERIOD_SECONDS], a little quieter and duller while it is behind. */
private class EightD(private val sampleRate: Int) {
    var left = 0f
    var right = 0f
    private var phase = 0.0
    private val inc = 2 * PI / (sampleRate * PERIOD_SECONDS)
    private var lowL = 0f
    private var lowR = 0f
    private val dull = onePole(sampleRate, 3_000f)

    fun process(l: Float, r: Float) {
        val m = (l + r) * 0.5f
        val s = (l - r) * 0.5f
        val pan = sin(phase).toFloat()
        val behind = cos(phase).toFloat() < 0f
        val angle = (pan + 1f) * (PI / 4).toFloat()
        val gl = cos(angle) * SQRT2
        val gr = sin(angle) * SQRT2
        val depth = if (behind) 0.8f + 0.2f * abs(pan) else 1f
        var outL = (m * gl + s * 0.3f) * depth
        var outR = (m * gr - s * 0.3f) * depth
        lowL += (outL - lowL) * dull
        lowR += (outR - lowR) * dull
        if (behind) {
            val back = (1f - abs(pan)) * 0.6f
            outL += (lowL - outL) * back
            outR += (lowR - outR) * back
        }
        left = outL
        right = outR
        phase += inc
        if (phase > 2 * PI) phase -= 2 * PI
    }

    companion object {
        const val PERIOD_SECONDS = 8.0
        val SQRT2 = kotlin.math.sqrt(2f) * 0.75f
    }
}

/** A ping-pong echo: 330 ms, each repeat on the other side, fading. */
private class Echo(sampleRate: Int) {
    var left = 0f
    var right = 0f
    private val size = (sampleRate * 0.33f).toInt().coerceAtLeast(1)
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var at = 0

    fun process(l: Float, r: Float) {
        val dl = bufL[at]
        val dr = bufR[at]
        bufL[at] = (l + r) * 0.5f + dr * FEEDBACK
        bufR[at] = dl * FEEDBACK
        at = (at + 1) % size
        left = l + dl * 0.5f
        right = r + dr * 0.5f
    }

    companion object {
        const val FEEDBACK = 0.45f
    }
}

/** Freeverb: eight damped combs and four allpasses a side, the right side's lines a little longer for width. */
private class Reverb(sampleRate: Int, hall: Boolean) {
    var left = 0f
    var right = 0f
    private val scale = sampleRate / 44_100f
    private val feedback = if (hall) 0.88f else 0.74f
    private val damp = if (hall) 0.25f else 0.45f
    private val combsL = COMBS.map { Comb((it * scale).toInt()) }
    private val combsR = COMBS.map { Comb(((it + SPREAD) * scale).toInt()) }
    private val passL = PASSES.map { AllPass((it * scale).toInt()) }
    private val passR = PASSES.map { AllPass(((it + SPREAD) * scale).toInt()) }

    fun process(l: Float, r: Float) {
        val input = (l + r) * GAIN
        var outL = 0f
        var outR = 0f
        for (c in combsL) outL += c.process(input, feedback, damp)
        for (c in combsR) outR += c.process(input, feedback, damp)
        for (a in passL) outL = a.process(outL)
        for (a in passR) outR = a.process(outR)
        left = l * 0.6f + outL * WET
        right = r * 0.6f + outR * WET
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var at = 0
        private var store = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val out = buf[at]
            store = out * (1f - damp) + store * damp
            buf[at] = input + store * feedback
            at = (at + 1) % buf.size
            return out
        }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(1))
        private var at = 0
        fun process(input: Float): Float {
            val held = buf[at]
            buf[at] = input + held * 0.5f
            at = (at + 1) % buf.size
            return held - input
        }
    }

    companion object {
        val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val PASSES = intArrayOf(556, 441, 341, 225)
        const val SPREAD = 23
        const val GAIN = 0.015f
        const val WET = 1.6f
    }
}

/** Two voices a side, delayed 12 to 24 ms and swept slowly in opposite directions. */
private class Chorus(private val sampleRate: Int) {
    var left = 0f
    var right = 0f
    private val size = (sampleRate * 0.04f).toInt() + 2
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var at = 0
    private var phase = 0.0
    private val inc = 2 * PI * RATE_HZ / sampleRate

    fun process(l: Float, r: Float) {
        bufL[at] = l
        bufR[at] = r
        val sweep = sin(phase).toFloat()
        val dL = (BASE_MS + DEPTH_MS * sweep) * sampleRate / 1000f
        val dR = (BASE_MS - DEPTH_MS * sweep) * sampleRate / 1000f
        left = (l + read(bufL, dL)) * 0.7f
        right = (r + read(bufR, dR)) * 0.7f
        at = (at + 1) % size
        phase += inc
        if (phase > 2 * PI) phase -= 2 * PI
    }

    private fun read(buf: FloatArray, delay: Float): Float {
        val pos = at - delay
        val base = kotlin.math.floor(pos).toInt()
        val frac = pos - base
        val a = buf[((base % size) + size) % size]
        val b = buf[(((base + 1) % size) + size) % size]
        return a + (b - a) * frac
    }

    companion object {
        const val RATE_HZ = 0.6
        const val BASE_MS = 18f
        const val DEPTH_MS = 6f
    }
}

/** Takes the centre out above the bass: the sides stay, and the low end (usually centred) is kept. */
private class VocalRemover(sampleRate: Int) {
    var left = 0f
    var right = 0f
    private val coef = onePole(sampleRate, 180f)
    private var lowFirst = 0f
    private var low = 0f

    fun process(l: Float, r: Float) {
        val m = (l + r) * 0.5f
        val s = (l - r) * 0.5f
        lowFirst += (m - lowFirst) * coef
        low += (lowFirst - low) * coef
        left = low + s * 1.4f
        right = low - s * 1.4f
    }
}

/** A gentle compressor with make-up gain: -30 dB threshold, 3:1, 10 ms attack, 250 ms release. */
private class Compressor(sampleRate: Int) {
    var left = 0f
    var right = 0f
    private val attack = (1.0 - exp(-1.0 / (0.010 * sampleRate))).toFloat()
    private val release = (1.0 - exp(-1.0 / (0.250 * sampleRate))).toFloat()
    private var env = 0f

    fun process(l: Float, r: Float) {
        val level = max(abs(l), abs(r))
        env += (level - env) * if (level > env) attack else release
        val db = if (env > 1e-6f) 20f * log10(env) else -120f
        val over = db - THRESHOLD_DB
        val reductionDb = if (over > 0f) over * (1f - 1f / RATIO) else 0f
        val gain = 10f.pow((MAKEUP_DB - reductionDb) / 20f)
        left = l * gain
        right = r * gain
    }

    companion object {
        const val THRESHOLD_DB = -30f
        const val RATIO = 3f
        const val MAKEUP_DB = 12f
    }
}

private fun onePole(sampleRate: Int, cutoffHz: Float): Float =
    (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate.coerceAtLeast(1))).toFloat()
