package com.cosmotune.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialDspTest {

    private val rate = 48_000

    /** [frames] of a constant stereo pair, run through [dsp] with [preset] on. */
    private fun run(dsp: SpatialDsp, preset: SpatialPreset, left: Float, right: Float, frames: Int = rate): FloatArray {
        val block = FloatArray(frames * 2) { if (it % 2 == 0) left else right }
        dsp.process(block, block.size, enabled = true, preset = preset, renderSurround = false)
        return block
    }

    @Test
    fun `crossfeed keeps a centred sound at its level`() {
        val out = run(SpatialDsp(rate, 2), SpatialPreset.CROSSFEED_GENTLE, 0.5f, 0.5f)
        assertEquals(0.5f, out[out.size - 2], 0.01f)
        assertEquals(0.5f, out[out.size - 1], 0.01f)
    }

    @Test
    fun `crossfeed sends the low end across at the preset's level`() {
        for (preset in listOf(SpatialPreset.CROSSFEED_GENTLE, SpatialPreset.CROSSFEED_CHU_MOY, SpatialPreset.CROSSFEED_MEIER)) {
            val out = run(SpatialDsp(rate, 2), preset, 0.5f, 0f)
            val direct = out[out.size - 2]
            val crossed = out[out.size - 1]
            val expected = 10.0.pow(-preset.feedDb / 20.0).toFloat()
            assertEquals(expected, crossed / direct, 0.01f, preset.name)
        }
    }

    @Test
    fun `mono folds both sides to the middle`() {
        val out = run(SpatialDsp(rate, 2), SpatialPreset.MONO, 1f, 0f)
        assertEquals(0.5f, out[out.size - 2], 1e-4f)
        assertEquals(0.5f, out[out.size - 1], 1e-4f)
    }

    @Test
    fun `switching on glides rather than jumping`() {
        val dsp = SpatialDsp(rate, 2)
        val out = run(dsp, SpatialPreset.MONO, 1f, 0f, frames = 64)
        // A few frames in, the left side has barely moved towards the middle.
        assertTrue(out[2 * 10] > 0.99f)
    }

    @Test
    fun `off and never on leaves the samples untouched`() {
        val dsp = SpatialDsp(rate, 2)
        val block = FloatArray(512) { (it % 7) / 7f }
        val copy = block.copyOf()
        dsp.process(block, block.size, enabled = false, preset = SpatialPreset.SPEAKERS_NEAR, renderSurround = false)
        assertTrue(block.contentEquals(copy))
    }

    @Test
    fun `a speaker on the right reaches the right ear first`() {
        val head = SpatialDsp.HeadRenderer(rate, floatArrayOf(-30f, 30f))
        val toLeft = head.delayOf(source = 1, ear = 0)
        val toRight = head.delayOf(source = 1, ear = 1)
        assertTrue(toRight < toLeft)
        // Woodworth's model: about a quarter of a millisecond between the ears at 30 degrees.
        val itdMs = (toLeft - toRight) / rate * 1_000f
        assertTrue(abs(itdMs - 0.26f) < 0.06f, "ITD $itdMs ms")
    }

    @Test
    fun `binaural surround writes two ears and silences the rest`() {
        val dsp = SpatialDsp(rate, 6)
        val frames = 4_800
        // The centre channel alone.
        val block = FloatArray(frames * 6) { if (it % 6 == 2) 0.5f else 0f }
        dsp.process(block, block.size, enabled = true, preset = SpatialPreset.DEFAULT, renderSurround = true)
        val last = (frames - 1) * 6
        assertEquals(block[last], block[last + 1], 1e-5f)
        assertTrue(block[last] > 0.05f)
        for (c in 2 until 6) assertEquals(0f, block[last + c])
    }

    @Test
    fun `surround is left alone unless asked for`() {
        val dsp = SpatialDsp(rate, 6)
        val block = FloatArray(600) { 0.25f }
        dsp.process(block, block.size, enabled = true, preset = SpatialPreset.DEFAULT, renderSurround = false)
        assertTrue(block.all { it == 0.25f })
    }
}
