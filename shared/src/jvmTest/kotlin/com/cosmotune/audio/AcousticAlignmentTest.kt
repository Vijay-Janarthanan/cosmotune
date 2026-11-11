package com.cosmotune.audio

import kotlin.test.*
import kotlin.random.Random

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
        assertTrue(meter.read(1000, 10)!!.all { kotlin.math.abs(it - .5f) < .001 })
        assertNull(meter.read(990, 10))
    }
}
