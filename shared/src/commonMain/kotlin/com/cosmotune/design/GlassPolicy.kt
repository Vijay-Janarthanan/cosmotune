package com.cosmotune.design

/** How a floating surface is drawn: blurred glass, or a nearly opaque tint with the same edge and shadow. */
enum class GlassMode { BLUR, SOLID }

/**
 * When glass may blur (design formulas section 5, Review Focus 5). Blur is allowed only on Android 12 (API 31) and newer or on
 * the desktop ([sdkInt] null), and never with "Reduce dynamic blur" on, on a low-RAM phone or over the video.
 */
object GlassPolicy {
    /** The tint over the blur: surfaceHigh at 64 %. */
    const val TINT_ALPHA = 0.64f

    /** The tint when nothing is blurred: surfaceHigh at 96 %. */
    const val SOLID_ALPHA = 0.96f

    private const val FIRST_BLURRING_SDK = 31

    fun mode(sdkInt: Int?, reduceBlur: Boolean, lowRamDevice: Boolean, overVideo: Boolean): GlassMode =
        if (reduceBlur || lowRamDevice || overVideo || (sdkInt != null && sdkInt < FIRST_BLURRING_SDK)) GlassMode.SOLID else GlassMode.BLUR
}
