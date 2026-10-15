package com.cosmotune.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.argbOf

@Stable
class CosmoPaletteState(initial: CoverColors, dark: Boolean) {
    /** The palette shown or heading for. Changes once per cover: safe to read in composition. */
    var target: CoverColors by mutableStateOf(initial)
        private set
    var targetDark: Boolean = dark
        private set

    /** The colour of [field] on screen now. Read it only inside draw, layout or graphicsLayer lambdas. */
    fun liveArgb(field: Int): Int = target.argbOf(field)

    suspend fun show(next: CoverColors, dark: Boolean, reducedMotion: Boolean, animate: Boolean) {
        target = next
        targetDark = dark
    }
}

/** Every colour a new surface needs. [palette] in composition; [live] only in draw-phase lambdas. */
@Stable
class CosmoColors internal constructor(private val state: CosmoPaletteState) {
    val palette: CoverColors get() = state.target
    fun live(field: Int): Color = Color(state.liveArgb(field))
}

@Stable
class CosmoShellState internal constructor(val settings: CosmoLookSettings, dark: Boolean) {
    val palette = CosmoPaletteState(CosmoBrandPalettes.of(dark), dark)
    val colors = CosmoColors(palette)
}

/** The palette of a cover with no colour: Cosmotune's own apricot (01-dynamic-colour.md section 3, rule 6). */
object CosmoBrandPalettes {
    private val darkBrand by lazy { CoverPalette.fromPixels(IntArray(0), 0, 0, dark = true) }
    private val lightBrand by lazy { CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false) }
    fun of(dark: Boolean): CoverColors = if (dark) darkBrand else lightBrand
}
