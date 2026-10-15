package com.cosmotune.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.cosmotune.theme.CoverColors

/**
 * The cover palette as a Material scheme, so the old screens follow the cover (handoff section 2, plus onBackground). Material's
 * error family is the palette's danger family (the v3 delta): a fixed red that does not follow the cover.
 */
fun CoverColors.toColorScheme(base: ColorScheme): ColorScheme {
    val text = Color(textPrimary)
    return base.copy(
        primary = Color(accent), onPrimary = Color(onAccent),
        primaryContainer = Color(accentContainer), onPrimaryContainer = Color(onAccentContainer),
        background = Color(backgroundBottom), onBackground = text,
        surface = Color(surface), onSurface = text,
        surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(textSecondary),
        surfaceTint = Color(accent),
        surfaceContainerLowest = Color(backgroundBottom), surfaceContainerLow = Color(surface),
        surfaceContainer = Color(surfaceHigh), surfaceContainerHigh = Color(surfaceHigh),
        surfaceContainerHighest = Color(surfaceVariant),
        outline = text.copy(alpha = 0.14f), outlineVariant = text.copy(alpha = 0.08f),
        scrim = Color(scrim),
        error = Color(danger), onError = Color(onDanger),
        errorContainer = Color(dangerContainer), onErrorContainer = Color(onDangerContainer),
    )
}
