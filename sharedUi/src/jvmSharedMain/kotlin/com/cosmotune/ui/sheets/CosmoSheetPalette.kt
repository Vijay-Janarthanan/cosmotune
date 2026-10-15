package com.cosmotune.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.cosmotune.ui.theme.LocalCosmoColors
import com.music.bitchord.ui.theme.ArtworkPalette

/** The rule between two groups of rows (`.menu3 hr`): the ink at 10 %. */
internal const val SHEET_DIVIDER_ALPHA = 0.10f

/**
 * The cover palette of the song that is playing, shaped as an [ArtworkPalette]: the old song and album sheets read their colours
 * from one (`palette.accent`, `palette.divider`, `palette.onBackground` ...), and in the new look every floating layer is the
 * cover-tinted glass of the playing song, not a wash of the sheet's own track. So the rows a sheet keeps read the cover palette
 * through this.
 *
 * `background` and `wash` are `surfaceHigh` (the glass the sheet stands on), `elevated` is `surfaceVariant`, the text colours are
 * `textPrimary` and `textSecondary`, the divider is `textPrimary` at 10 %. Read in composition, so it changes once per cover.
 */
@Composable
fun cosmoSheetPalette(): ArtworkPalette {
    val palette = LocalCosmoColors.current.palette
    return remember(palette) {
        val ink = Color(palette.textPrimary)
        val glass = Color(palette.surfaceHigh)
        ArtworkPalette(
            background = glass,
            wash = glass,
            elevated = Color(palette.surfaceVariant),
            accent = Color(palette.accent),
            onBackground = ink,
            onBackgroundVariant = Color(palette.textSecondary),
            divider = ink.copy(alpha = SHEET_DIVIDER_ALPHA),
        )
    }
}
