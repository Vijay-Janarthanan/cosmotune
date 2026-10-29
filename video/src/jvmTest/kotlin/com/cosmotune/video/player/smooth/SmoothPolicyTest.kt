package com.cosmotune.video.player.smooth

import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothPreset
import com.cosmotune.video.settings.SmoothnessSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions of frame smoothing: when it applies, where a drawn frame falls between two source frames, and when it steps down. */
class SmoothPolicyTest {
    private val ms = 1_000_000L
    private val smooth = SmoothnessSettings(mode = SmoothMode.SMOOTH)

    // ---- when it applies

    @Test
    fun offAndUnmeasuredVideosAreLeftAlone() {
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(SmoothnessSettings(), 24f, 60f, SmoothMode.SMOOTH))
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth, null, 60f, SmoothMode.SMOOTH), "nothing is assumed about a video not measured")
    }

    @Test
    fun onlyBelow30LeavesFastVideosAlone() {
        assertEquals(SmoothMode.SMOOTH, SmoothPolicy.effectiveMode(smooth, 23.976f, 60f, SmoothMode.SMOOTH))
        assertEquals(SmoothMode.SMOOTH, SmoothPolicy.effectiveMode(smooth, 25f, 60f, SmoothMode.SMOOTH))
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth, 29.97f, 120f, SmoothMode.SMOOTH), "29.97 is a 30 fps video")
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth, 60f, 120f, SmoothMode.SMOOTH))
        assertEquals(SmoothMode.SMOOTH, SmoothPolicy.effectiveMode(smooth.copy(onlyBelow30 = false), 30f, 120f, SmoothMode.SMOOTH), "the switch is off")
    }

    @Test
    fun aScreenThatShowsNoMoreThanTheVideoHasHasNothingToFillIn() {
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth, 24f, 24f, SmoothMode.SMOOTH))
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth.copy(onlyBelow30 = false), 50f, 60f, SmoothMode.SMOOTH))
        assertEquals(60f, SmoothPolicy.outputFps(SmoothnessSettings(), 60f))
        assertEquals(48f, SmoothPolicy.outputFps(SmoothnessSettings(targetFps = 48), 60f))
        assertEquals(60f, SmoothPolicy.outputFps(SmoothnessSettings(targetFps = 120), 60f), "a screen cannot show more than it refreshes")
    }

    @Test
    fun theGovernorsCeilingCapsTheMode() {
        assertEquals(SmoothMode.BLEND, SmoothPolicy.effectiveMode(smooth, 24f, 60f, SmoothMode.BLEND))
        assertEquals(SmoothMode.OFF, SmoothPolicy.effectiveMode(smooth, 24f, 60f, SmoothMode.OFF))
        assertEquals(SmoothMode.BLEND, SmoothPolicy.effectiveMode(smooth.copy(mode = SmoothMode.BLEND), 24f, 60f, SmoothMode.SMOOTH), "never above what was asked")
    }

    @Test
    fun theReasonForNotSmoothingIsSaidInWords() {
        assertNull(SmoothPolicy.whyNot(smooth, 24f, 60f, SmoothMode.SMOOTH), "it is used")
        assertNull(SmoothPolicy.whyNot(SmoothnessSettings(), 60f, 60f, SmoothMode.OFF), "it was not asked for")
        assertTrue(assertNotNull(SmoothPolicy.whyNot(smooth, 60f, 120f, SmoothMode.OFF)).contains("already 60 fps"))
        assertTrue(assertNotNull(SmoothPolicy.whyNot(smooth, 24f, 24f, SmoothMode.OFF)).contains("24 frames a second at most"))
    }

    // ---- the phase

    @Test
    fun theFrameBetweenTwoSourceFramesFallsByTheClock() {
        val interval = 41_708_333L // 23.976 fps
        val arrived = 1_000 * ms
        assertEquals(0f, SmoothPolicy.phase(arrived, arrived, interval, interval))
        assertEquals(0.5f, SmoothPolicy.phase(arrived + interval / 2, arrived, interval, interval), 0.001f)
        assertEquals(1f, SmoothPolicy.phase(arrived + interval, arrived, interval, interval))
        assertEquals(1f, SmoothPolicy.phase(arrived + 3 * interval, arrived, interval, interval), "past the next frame: the newer one stays")
        assertEquals(0f, SmoothPolicy.phase(arrived - 5 * ms, arrived, interval, interval), "never before the older frame")
    }

    @Test
    fun aPairThatIsNotTwoConsecutiveFramesIsNotInterpolated() {
        val interval = 41 * ms
        val arrived = 1_000 * ms
        val half = arrived + interval / 2
        assertEquals(1f, SmoothPolicy.phase(half, arrived, 5 * interval, interval), "a seek or a stall between the frames")
        assertEquals(1f, SmoothPolicy.phase(half, arrived, interval / 5, interval), "the same picture twice")
        assertEquals(0.5f, SmoothPolicy.phase(half, arrived, 2 * interval, interval), 0.001f, "one dropped frame is still a smooth run")
        assertEquals(1f, SmoothPolicy.phase(half, arrived, interval, 0), "no interval known")
    }

    @Test
    fun aNonDisplayTargetDrawsOnlyWhenItsClockAdvances() {
        val frame = 1_000_000_000L / 60
        // 30 fps on a 60 Hz screen: every second vsync draws
        val drawn = (0 until 60).count { i ->
            SmoothPolicy.outputIndex(i * frame, 30) != SmoothPolicy.outputIndex((i - 1) * frame, 30)
        }
        assertEquals(30, drawn)
        assertFalse(SmoothPolicy.outputIndex(1 * ms, 0) == SmoothPolicy.outputIndex(2 * ms, 0), "a display-rate target draws at every call")
    }

    // ---- the cross-fade and the tuning

    @Test
    fun theCrossFadeIsSoftAtFullStrengthAndAStepAtNone() {
        assertEquals(0.5f, SmoothPolicy.blendWeight(0.5f, 100), 0.001f)
        assertEquals(0.25f, SmoothPolicy.blendWeight(0.25f, 100), 0.001f)
        assertEquals(0f, SmoothPolicy.blendWeight(0.49f, 0), "a hard switch")
        assertEquals(1f, SmoothPolicy.blendWeight(0.51f, 0))
        assertTrue(SmoothPolicy.blendWeight(0.45f, 5) < 0.1f, "and almost one at the lowest strengths")
        val soft = SmoothPolicy.blendWeight(0.4f, 80)
        val firm = SmoothPolicy.blendWeight(0.4f, 20)
        assertTrue(soft > firm, "softer fades change sooner: $soft against $firm")
    }

    @Test
    fun strengthAndPresetTuneTheSearch() {
        val gentle = SmoothPolicy.motionParams(SmoothPreset.FILM, 0)
        val bold = SmoothPolicy.motionParams(SmoothPreset.FILM, 100)
        assertTrue(bold.tolerance > gentle.tolerance)
        assertTrue(SmoothPolicy.motionParams(SmoothPreset.ANIME, 50).range > SmoothPolicy.motionParams(SmoothPreset.FILM, 50).range)
    }

    // ---- stepping down

    @Test
    fun smoothStepsDownToBlendAndThenOffAfterSlowWork() {
        val governor = SmoothGovernor(slowLimit = 6)
        repeat(5) { assertFalse(governor.report(50.0, 30.0), "five slow answers are not yet repeatedly") }
        assertEquals(SmoothMode.SMOOTH, governor.ceiling)
        assertTrue(governor.report(50.0, 30.0), "the sixth in a row")
        assertEquals(SmoothMode.BLEND, governor.ceiling)
        assertTrue(assertNotNull(governor.notice).contains("Blend"))
        repeat(5) { governor.report(50.0, 30.0) }
        assertTrue(governor.report(50.0, 30.0))
        assertEquals(SmoothMode.OFF, governor.ceiling)
        assertTrue(assertNotNull(governor.notice).contains("switched off"))
        assertFalse(governor.report(50.0, 30.0), "nothing lower")
    }

    @Test
    fun anOccasionalSlowFrameDoesNotStepDown() {
        val governor = SmoothGovernor(slowLimit = 6)
        repeat(200) { i -> assertFalse(governor.report(if (i % 5 == 0) 50.0 else 10.0, 30.0)) }
        assertEquals(SmoothMode.SMOOTH, governor.ceiling)
        assertNull(governor.notice)
    }

    @Test
    fun aMostlySlowRunStepsDownEvenWithGapsBetween() {
        val governor = SmoothGovernor(slowLimit = 6, window = 24)
        var stepped = false
        for (i in 0 until 60) if (governor.report(if (i % 4 == 3) 10.0 else 50.0, 30.0)) stepped = true
        assertTrue(stepped, "three quarters of the answers were too slow")
    }

    @Test
    fun theCeilingComesBackOnAReset() {
        val governor = SmoothGovernor(slowLimit = 3)
        repeat(3) { governor.report(99.0, 10.0) }
        assertEquals(SmoothMode.BLEND, governor.ceiling)
        governor.reset()
        assertEquals(SmoothMode.SMOOTH, governor.ceiling)
        assertNull(governor.notice)
    }

    // ---- which kind of picture

    @Test
    fun autoReadsDrawnAnimationFromHowOftenThePictureRepeats() {
        val resolver = PresetResolver()
        assertEquals(SmoothPreset.FILM, resolver.resolve(SmoothPreset.AUTO), "nothing seen yet")
        repeat(48) { i -> resolver.note(still = i % 3 != 0) } // two pairs of three repeat the picture
        assertEquals(SmoothPreset.ANIME, resolver.resolve(SmoothPreset.AUTO))
        assertEquals(SmoothPreset.FILM, resolver.resolve(SmoothPreset.FILM), "a chosen preset is kept")
        repeat(48) { resolver.note(still = false) }
        assertEquals(SmoothPreset.FILM, resolver.resolve(SmoothPreset.AUTO))
    }
}
