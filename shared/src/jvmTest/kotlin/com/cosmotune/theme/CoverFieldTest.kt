package com.cosmotune.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverFieldTest {
    // Every colour of the handoff table, valued by its index. track and outline are the palette's two translucent
    // neutrals, which the animation does not blend (they are not in the handoff table), so they sit beyond the table.
    private val numbered = CoverColors(
        seed = 0, ambient = 1,
        backgroundTop = 2, backgroundMid = 3, backgroundBottom = 4,
        surface = 5, surfaceHigh = 6, surfaceVariant = 7,
        textPrimary = 8, textSecondary = 9, textTertiary = 10,
        accent = 11, accentStrong = 12, onAccent = 13,
        buttonGradientStart = 14, buttonGradientEnd = 15,
        accentContainer = 16, onAccentContainer = 17,
        glow = 18, shadow = 19, scrim = 20,
        track = 100, outline = 101,
        isFallback = false,
    )

    @Test fun theFieldArrayFollowsTheIndexConstants() {
        assertEquals((0 until 21).toList(), numbered.toFieldArray().toList())
        assertEquals(CoverField.COUNT, numbered.toFieldArray().size)
    }

    @Test fun argbOfReadsOneColourByItsIndex() {
        assertEquals(0, numbered.argbOf(CoverField.SEED))
        assertEquals(8, numbered.argbOf(CoverField.TEXT_PRIMARY))
        assertEquals(11, numbered.argbOf(CoverField.ACCENT))
        assertEquals(20, numbered.argbOf(CoverField.SCRIM))
    }

    @Test fun everyColourOfThePaletteIsInTheTableOrNamedAsLeftOut() {
        // A colour that P3-1b or a later change adds to CoverColors must be given an index here, or be named below.
        val leftOut = setOf("track", "outline")
        val ints = CoverColors::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && !it.isSynthetic }
            .map { it.name }
        assertEquals(CoverField.COUNT, ints.count { it !in leftOut }, "colours of CoverColors: $ints")
        assertEquals(leftOut, ints.filter { it in leftOut }.toSet())
    }
}
