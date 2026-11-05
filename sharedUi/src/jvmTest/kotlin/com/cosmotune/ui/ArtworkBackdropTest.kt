package com.cosmotune.ui

import androidx.compose.ui.graphics.Color
import com.cosmotune.ui.theme.readableBehind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The player's artwork backdrop holds every colour it draws to a luminance the text keeps 4.5:1 on (the luminance cap). */
class ArtworkBackdropTest {
    private fun lum(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    private fun contrast(a: Color, b: Color) = (max(lum(a), lum(b)) + 0.05) / (min(lum(a), lum(b)) + 0.05)

    private val bright = listOf(Color(0xFFFFD54F), Color(0xFF7FDBFF), Color(0xFFFF8A65), Color(0xFFE0E0E0), Color(0xFF66BB6A), Color(0xFFBA68C8))

    @Test fun lightTextKeeps4Point5To1OnEveryCappedColour() {
        val ink = Color(0xFFF4F4F6)
        for (c in bright) assertTrue(contrast(ink, readableBehind(c, ink)) >= 4.5 - 1e-3, "${c} -> ${contrast(ink, readableBehind(c, ink))}")
    }

    @Test fun darkTextKeeps4Point5To1Too() {
        val ink = Color(0xFF16161A)
        for (c in listOf(Color(0xFF3A2A5A), Color(0xFF1E3A5F), Color(0xFF5A1E1E))) assertTrue(contrast(ink, readableBehind(c, ink)) >= 4.5 - 1e-3)
    }

    @Test fun aColourThatAlreadyReadsIsLeftAlone() {
        val ink = Color.White
        val deep = Color(0xFF2B1B4A)
        assertEquals(deep, readableBehind(deep, ink))
    }
}
