package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The numbers glass takes from the policy, so the Compose side holds no literal of its own. */
class GlassNumbersTest {
    @Test fun blurIsTwentyTwoOnThePhoneAndThirtyTwoOnTheDesktop() {
        assertEquals(22f, GlassPolicy.blurDp(sdkInt = 34))
        assertEquals(22f, GlassPolicy.blurDp(sdkInt = 31))
        assertEquals(32f, GlassPolicy.blurDp(sdkInt = null))
    }

    @Test fun theFillIsTheGlassAlphaWhenBlurredAndTheSolidAlphaWhenNot() {
        assertEquals(0.52f, GlassPolicy.fillAlpha(GlassMode.BLUR, dark = true))
        assertEquals(0.62f, GlassPolicy.fillAlpha(GlassMode.BLUR, dark = false))
        assertEquals(0.94f, GlassPolicy.fillAlpha(GlassMode.SOLID, dark = true))
        assertEquals(0.94f, GlassPolicy.fillAlpha(GlassMode.SOLID, dark = false))
    }

    @Test fun theCoverTintIsAHundredDegreeWashFadingOutAtSeventyPercent() {
        assertEquals(100f, GlassPolicy.COVER_TINT_ANGLE_DEG)
        assertEquals(0.7f, GlassPolicy.COVER_TINT_FADE_END)
        assertEquals(0.46f, GlassPolicy.COVER_TINT_ALPHA)
    }

    @Test fun theEdgeAndShadowNumbersOfTheSpec() {
        assertEquals(0.14f, GlassPolicy.edgeRingAlpha(dark = true))
        assertEquals(0.08f, GlassPolicy.edgeRingAlpha(dark = false))
        assertEquals(0.24f, GlassPolicy.edgeTopAlpha(dark = true))
        assertEquals(0.85f, GlassPolicy.edgeTopAlpha(dark = false))
        assertEquals(0.50f, GlassPolicy.shadowAlpha(dark = true))
        assertEquals(0.22f, GlassPolicy.shadowAlpha(dark = false))
        assertEquals(12f, GlassPolicy.SHADOW_ELEVATION_DP)
    }
}
