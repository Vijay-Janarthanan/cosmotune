package com.cosmotune.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.cosmotune.theme.CoverPalette
import com.cosmotune.ui.theme.toColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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

    @Test fun theErrorFamilyIsTheDangerFamily() {
        // Material's error, onError, errorContainer and onErrorContainer are the palette's fixed red family (v3 delta), so a
        // dialog or a text field that still asks Material for "error" gets the same red as the new screens.
        for (dark in listOf(true, false)) {
            val p = CoverPalette.fromPixels(IntArray(64 * 64) { 0xFF466044.toInt() }, 64, 64, dark = dark)
            val s = p.toColorScheme(if (dark) darkColorScheme() else lightColorScheme())
            assertEquals(Color(p.danger), s.error, "error (dark = $dark)")
            assertEquals(Color(p.onDanger), s.onError, "onError (dark = $dark)")
            assertEquals(Color(p.dangerContainer), s.errorContainer, "errorContainer (dark = $dark)")
            assertEquals(Color(p.onDangerContainer), s.onErrorContainer, "onErrorContainer (dark = $dark)")
        }
    }

    @Test fun theErrorColoursAreTheDesignersRedNotMaterialsStockOnes() {
        val dark = green.toColorScheme(darkColorScheme())
        assertEquals(Color(0xFFF2716A), dark.error)
        assertEquals(Color(0xFF1A0E0D), dark.onError)
        assertEquals(Color(0xFF642724), dark.errorContainer)
        assertEquals(Color(0xFFFFDEDB), dark.onErrorContainer)
        val base = lightColorScheme()
        val light = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false).toColorScheme(base)
        assertEquals(Color(0xFFBA2B28), light.error)
        assertEquals(Color(0xFFFFFFFF), light.onError)
        assertEquals(Color(0xFFFFD8D4), light.errorContainer)
        assertEquals(Color(0xFF6C1614), light.onErrorContainer)
        assertNotEquals(base.error, light.error)
    }

    @Test fun keepsWhatTheBridgeDoesNotOwn() {
        val base = lightColorScheme()
        val s = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false).toColorScheme(base)
        assertEquals(base.inverseSurface, s.inverseSurface)
        assertEquals(base.surfaceBright, s.surfaceBright)
    }

    @Test fun tonalControlsFollowTheArtworkPaletteInBothThemes() {
        for (dark in listOf(true, false)) {
            val palette = CoverPalette.fromPixels(IntArray(64) { 0xff466044.toInt() }, 8, 8, dark = dark)
            val scheme = palette.toColorScheme(if (dark) darkColorScheme() else lightColorScheme())
            assertEquals(scheme.primaryContainer, scheme.secondaryContainer)
            assertEquals(scheme.onPrimaryContainer, scheme.onSecondaryContainer)
            assertEquals(scheme.primaryContainer, scheme.tertiaryContainer)
        }
    }
}
