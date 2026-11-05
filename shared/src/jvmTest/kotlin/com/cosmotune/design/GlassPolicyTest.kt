package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** Review Focus 5: glass is solid below API 31, with reduced blur, on low-RAM phones and over video. */
class GlassPolicyTest {

    private val sdks: List<Int?> = listOf(26, 30, 31, 35, null)

    @Test
    fun theFullTruthTable() {
        for (sdk in sdks) for (reduce in listOf(false, true)) for (low in listOf(false, true)) for (video in listOf(false, true)) {
            val oldAndroid = sdk != null && sdk < 31
            val expected = if (reduce || low || video || oldAndroid) GlassMode.SOLID else GlassMode.BLUR
            assertEquals(
                expected,
                GlassPolicy.mode(sdk, reduce, low, video),
                "sdk=$sdk reduceBlur=$reduce lowRam=$low overVideo=$video",
            )
        }
    }

    @Test
    fun blurOnlyWhenNothingSaysOtherwise() {
        assertEquals(GlassMode.BLUR, GlassPolicy.mode(31, reduceBlur = false, lowRamDevice = false, overVideo = false))
        assertEquals(GlassMode.BLUR, GlassPolicy.mode(null, reduceBlur = false, lowRamDevice = false, overVideo = false))
        assertEquals(GlassMode.SOLID, GlassPolicy.mode(30, reduceBlur = false, lowRamDevice = false, overVideo = false))
    }

    @Test
    fun theGlassNumbersAreTheBcSpecsValues() {
        assertEquals(0.52f, GlassPolicy.TINT_ALPHA)
        assertEquals(0.62f, GlassPolicy.TINT_ALPHA_LIGHT)
        assertEquals(0.46f, GlassPolicy.COVER_TINT_ALPHA)
        assertEquals(0.94f, GlassPolicy.SOLID_ALPHA)
        assertEquals(22f, GlassPolicy.BLUR_DP)
        assertEquals(32f, GlassPolicy.BLUR_DP_DESKTOP)
        assertEquals(1.6f, GlassPolicy.SATURATION)
        assertEquals(0.52f, GlassPolicy.tintAlpha(dark = true))
        assertEquals(0.62f, GlassPolicy.tintAlpha(dark = false))
    }
}
