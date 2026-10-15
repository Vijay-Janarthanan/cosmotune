package com.cosmotune.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.cosmotune.theme.CoverPalette
import com.cosmotune.ui.theme.toColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorSchemeBridgeTest {
    private val green = CoverPalette.fromPixels(IntArray(64 * 64) { 0xFF466044.toInt() }, 64, 64, dark = true)

    @Test fun followsTheHandoffTable() {
        val s = green.toColorScheme(darkColorScheme())
        assertEquals(Color(green.accent), s.primary)
        assertEquals(Color(green.onAccent), s.onPrimary)
        assertEquals(Color(green.accentContainer), s.primaryContainer)
        assertEquals(Color(green.onAccentContainer), s.onPrimaryContainer)
        assertEquals(Color(green.backgroundBottom), s.background)
        assertEquals(Color(green.textPrimary), s.onBackground)
        assertEquals(Color(green.surface), s.surface)
        assertEquals(Color(green.textPrimary), s.onSurface)
        assertEquals(Color(green.surfaceVariant), s.surfaceVariant)
        assertEquals(Color(green.textSecondary), s.onSurfaceVariant)
        assertEquals(Color(green.surfaceHigh), s.surfaceContainer)
        assertEquals(Color(green.surfaceVariant), s.surfaceContainerHighest)
        assertEquals(Color(green.textPrimary).copy(alpha = 0.14f), s.outline)
        assertEquals(Color(green.scrim), s.scrim)
    }

    @Test fun keepsWhatTheBridgeDoesNotOwn() {
        val base = lightColorScheme()
        val s = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false).toColorScheme(base)
        assertEquals(base.error, s.error)
        assertEquals(base.onError, s.onError)
    }
}
