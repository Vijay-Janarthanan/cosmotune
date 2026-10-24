package com.cosmotune.music.desktop

import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Automix transition sends (beat roll, reverb tail and the five textures): a bypassed send is the dry signal, every
 * send stays finite and bounded on full-scale noise, channels never bleed into each other, and a reset leaves silence.
 */
class DesktopTransitionFxTest {
    private val rate = 44_100
    private val textures: List<Pair<String, () -> DesktopTransitionTexture>> = listOf(
        "flanger" to { DesktopTransitionFlanger(rate, 2) },
        "crush" to { DesktopTransitionCrush(rate, 2) },
        "phaser" to { DesktopTransitionPhaser(rate, 2) },
        "vibrato" to { DesktopTransitionVibrato(rate, 2) },
        "stereo" to { DesktopTransitionStereo(rate, 2) },
    )

    /** Runs [frames] interleaved stereo frames through [send], left from [left], right from [right]. */
    private fun run(frames: Int, left: (Int) -> Float, right: (Int) -> Float, send: (Int, Float) -> Float): Pair<FloatArray, FloatArray> {
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        for (i in 0 until frames) {
            l[i] = send(0, left(i))
            r[i] = send(1, right(i))
        }
        return l to r
    }

    @Test fun everyTextureIsBoundedFiniteAndSilentOnSilence() {
        for ((name, make) in textures) {
            val fx = make().apply { reset() }
            val noise = Random(3)
            val (l, r) = run(rate * 5, { noise.nextFloat() * 2 - 1 }, { noise.nextFloat() * 2 - 1 }, fx::process)
            val peak = (l + r).maxOf { abs(it) }
            assertTrue((l + r).all { it.isFinite() }, "$name produced a non-finite sample")
            assertTrue(peak < 4f, "$name peaked at $peak")
            fx.reset()
            val (sl, sr) = run(rate, { 0f }, { 0f }, fx::process)
            assertEquals(0f, (sl + sr).maxOf { abs(it) }, "$name rang on after a reset")
        }
    }

    @Test fun aSoundOnOneChannelNeverReachesTheOther() {
        for ((name, make) in textures) {
            val fx = make().apply { reset() }
            val (_, right) = run(rate, { if (it % 1_000 == 0) 1f else 0f }, { 0f }, fx::process)
            assertEquals(0f, right.maxOf { abs(it) }, "$name leaked the left channel into the right")
        }
        val reverb = DesktopTransitionReverb(rate, 2)
        val (_, right) = run(rate, { if (it == 0) 1f else 0f }, { 0f }, reverb::process)
        assertEquals(0f, right.maxOf { abs(it) }, "the reverb is dual mono")
    }

    @Test fun theFlangerSweepsItsDelayOncePerFrameAtItsOwnRate() {
        // At 0.4 Hz the delay goes from its shortest to its longest in 1.25 s of audio; counted per interleaved
        // sample it did so twice as fast in stereo. An impulse train shows where the delayed copy lands.
        val fx = DesktopTransitionFlanger(rate, 2).apply { reset() }
        val (l, _) = run(rate * 3, { if (it % 2_000 == 0) 1f else 0f }, { 0f }, fx::process)
        val delays = (0 until rate * 3 step 2_000).mapNotNull { start ->
            (start + 1 until minOf(start + 400, l.size)).firstOrNull { abs(l[it]) > 0.5f }?.minus(start)
        }
        val shortest = delays.indices.minBy { delays[it] }
        val longest = delays.indices.maxBy { delays[it] }
        // Roughly a quarter-cycle apart in impulses (1.25 s / 2_000 frames ≈ 27), not half that.
        assertTrue(abs(longest - shortest) in 18..40, "sweep spacing ${abs(longest - shortest)} impulses: $delays")
        assertTrue(delays.max() <= (0.005 * rate).toInt() + 1 && delays.min() >= (0.0005 * rate).toInt() - 1, "$delays")
    }

    @Test fun theReverbTailDecaysAfterItsInputStops() {
        val reverb = DesktopTransitionReverb(rate, 1)
        val out = FloatArray(rate * 4) { reverb.process(0, if (it < rate / 10) Random(it).nextFloat() * 2 - 1 else 0f) }
        fun energy(from: Int, to: Int) = (from until to).sumOf { (out[it] * out[it]).toDouble() }
        val early = energy(rate / 10, rate / 2)
        val late = energy(rate * 3, rate * 4)
        assertTrue(early > 0.0 && out.all { it.isFinite() })
        assertTrue(late < early * 1e-3, "tail still at ${late / early} of its early energy after 3 s")
    }

    @Test fun aBypassedRollIsTheDrySignalAndALatchedRollRepeatsTheLastSliceQuieterEachTime() {
        val roll = DesktopTransitionBeatRepeat(rate, 1).apply { configure(0.01) }
        val slice = (0.01 * rate).toInt()
        // Before the latch, wet 0 / dry 1 is a plain pass-through while the ring records.
        for (i in 0 until slice * 3) assertEquals(i.toFloat(), roll.process(i.toFloat(), 0f, 1f))
        roll.latch()
        assertTrue(roll.engaged)
        val first = FloatArray(slice) { roll.process(0f, 1f, 0f) }
        val second = FloatArray(slice) { roll.process(0f, 1f, 0f) }
        // The frozen slice is the last one heard, oldest sample first.
        assertEquals((slice * 2).toFloat(), first[0])
        assertEquals((slice * 3 - 1).toFloat(), first[slice - 1])
        for (i in 1 until slice) assertTrue(abs(second[i]) < abs(first[i]), "repeat $i did not decay")
    }
}
