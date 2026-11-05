package com.cosmotune.design

import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverPalette

/**
 * The six sample covers of the look preview (the style sheet's cover picker): a 64 x 64 picture of vertical stripes in two
 * colours, which the palette engine reads like any real cover. Green, red-orange, blue, a hard yellow, a neon pink, and a
 * paper white that is nearly grey (the engine's no-colour case).
 */
object LookSamples {
    class Sample(val name: String, val a: Int, val b: Int)

    const val SIZE = 64
    private const val STRIPE = 8

    val all: List<Sample> = listOf(
        Sample("Green", 0xFF466044.toInt(), 0xFF9DC49B.toInt()),
        Sample("Red-orange", 0xFFD4552E.toInt(), 0xFF401E14.toInt()),
        Sample("Blue", 0xFF2D5A8A.toInt(), 0xFF8BBCF2.toInt()),
        Sample("Yellow", 0xFFFFE500.toInt(), 0xFF342702.toInt()),
        Sample("Neon", 0xFFFF2D95.toInt(), 0xFF3E1C29.toInt()),
        Sample("Paper", 0xFFF4F1EA.toInt(), 0xFFE8E4DC.toInt()),
    )

    /** The picture, row by row: eight columns of [Sample.a], eight of [Sample.b], and so on. */
    fun pixels(sample: Sample): IntArray = IntArray(SIZE * SIZE) { i -> if ((i % SIZE) / STRIPE % 2 == 0) sample.a else sample.b }

    /** The palette the engine makes of [sample] in the dark or the light theme. Takes a few milliseconds: call it off the main thread. */
    fun palette(sample: Sample, dark: Boolean): CoverColors = CoverPalette.fromPixels(pixels(sample), SIZE, SIZE, dark)
}
