package com.cosmotune.design

/** How a floating (C) surface is drawn: blurred glass, or a nearly opaque tint with the same edge and shadow. */
enum class GlassMode { BLUR, SOLID }

/**
 * When glass may blur, and the glass numbers of the B+C spec (`v3/01-components-spec-bc.md`, section "The glass surface").
 * Blur is allowed only on Android 12 (API 31) and newer or on the desktop ([sdkInt] null), and never with "Reduce dynamic
 * blur" on, on a low-RAM phone or over the video (Review Focus 5).
 */
object GlassPolicy {
    /** The `glass` colour's opacity over the blur in the dark theme: 52 %. */
    const val TINT_ALPHA = 0.52f

    /** The same in the light theme: 62 %. */
    const val TINT_ALPHA_LIGHT = 0.62f

    /** The cover's `accentContainer` laid over the glass, fading out towards 70 % of the width: 46 %. */
    const val COVER_TINT_ALPHA = 0.46f

    /** The tint when nothing is blurred: `surfaceHigh` at 94 %. Always used under reduced transparency. */
    const val SOLID_ALPHA = 0.94f

    const val BLUR_DP = 22f
    const val BLUR_DP_DESKTOP = 32f
    const val SATURATION = 1.6f

    /** The cover tint runs along 100 degrees (CSS angle) and is gone at 70 % of the way. */
    const val COVER_TINT_ANGLE_DEG = 100f
    const val COVER_TINT_FADE_END = 0.7f

    /** The shadow under floating glass is a lift of this many dp (the design's `0 10 30 -12` is about a Material elevation of 12). */
    const val SHADOW_ELEVATION_DP = 12f

    private const val FIRST_BLURRING_SDK = 31

    fun tintAlpha(dark: Boolean): Float = if (dark) TINT_ALPHA else TINT_ALPHA_LIGHT

    /** Backdrop blur radius: 22 dp on a phone, 32 dp on the desktop ([sdkInt] null). */
    fun blurDp(sdkInt: Int?): Float = if (sdkInt == null) BLUR_DP_DESKTOP else BLUR_DP

    /** Opacity of the `surfaceHigh` fill: the glass 52 % (62 % in the light theme) over a blur, the nearly solid 94 % without one. */
    fun fillAlpha(mode: GlassMode, dark: Boolean): Float = if (mode == GlassMode.SOLID) SOLID_ALPHA else tintAlpha(dark)

    /** The specular edge: an inner 1 dp ring (white 14 %, light theme black 8 %) and a top line (white 24 %, light theme 85 %). */
    fun edgeRingAlpha(dark: Boolean): Float = if (dark) 0.14f else 0.08f
    fun edgeTopAlpha(dark: Boolean): Float = if (dark) 0.24f else 0.85f

    fun shadowAlpha(dark: Boolean): Float = if (dark) 0.50f else 0.22f

    fun mode(sdkInt: Int?, reduceBlur: Boolean, lowRamDevice: Boolean, overVideo: Boolean): GlassMode =
        if (reduceBlur || lowRamDevice || overVideo || (sdkInt != null && sdkInt < FIRST_BLURRING_SDK)) GlassMode.SOLID else GlassMode.BLUR
}
