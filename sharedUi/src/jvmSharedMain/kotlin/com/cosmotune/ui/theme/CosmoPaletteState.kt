package com.cosmotune.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.PaletteBlend
import com.cosmotune.theme.toFieldArray

/**
 * The palette on screen, and the fade to the next one.
 *
 * [target] is the palette being shown or heading for. It changes once per cover, before the fade's first frame, and is the
 * only thing composition reads (text, icons, the Material scheme). [liveArgb] is the colour on screen this frame, from the
 * [PaletteBlend] at the fade's progress: read it only in draw, layout or graphicsLayer lambdas, so a frame of the fade
 * redraws a few surfaces and recomposes nothing.
 */
@Stable
class CosmoPaletteState(initial: CoverColors, dark: Boolean) {
    /** The palette shown or heading for. Changes once per cover: safe to read in composition. */
    var target: CoverColors by mutableStateOf(initial)
        private set
    var targetDark: Boolean = dark
        private set
    private val blend = PaletteBlend(initial.toFieldArray())
    private val progress = Animatable(1f)

    /** The colour of [field] (a [com.cosmotune.theme.CoverField] index) on screen now. Read it only in draw-phase lambdas. */
    fun liveArgb(field: Int): Int = blend.argbAt(field, progress.value)

    /**
     * Heads for [next]. With [animate] false (the first cover after launch, a theme flip) it is there at once, with no frame
     * needed; otherwise it fades over 700 ms (150 ms with [reducedMotion]) from the blend that is on screen now, also in the
     * middle of an earlier fade.
     */
    suspend fun show(next: CoverColors, dark: Boolean, reducedMotion: Boolean, animate: Boolean) {
        if (next == target && dark == targetDark) return finishFade(reducedMotion, animate)
        blend.retarget(next.toFieldArray(), progress.value)   // from the blend on screen, also mid-fade
        target = next                                         // once, before the first frame: text, icons, Material
        targetDark = dark
        if (!animate) { progress.snapTo(1f); return }         // no frame is needed (and none is waited for)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (reducedMotion) REDUCED_MS else PALETTE_MS, easing = FADE_EASING))
    }

    /**
     * Brings a fade that was cut short to its end. The effect that runs [show] is restarted whenever the cover's address
     * changes; a restart that finds the palette it is heading for already right must not leave the screen half-way there.
     */
    suspend fun finishFade(reducedMotion: Boolean, animate: Boolean = true) {
        if (progress.value >= 1f) return
        if (!animate) { progress.snapTo(1f); return }
        val ms = if (reducedMotion) REDUCED_MS else (PALETTE_MS * (1f - progress.value)).toInt().coerceAtLeast(REDUCED_MS)
        progress.animateTo(1f, tween(ms, easing = FADE_EASING))
    }

    private companion object {
        const val REDUCED_MS = com.cosmotune.design.Springs.REDUCED_MS
        const val PALETTE_MS = com.cosmotune.design.Springs.PALETTE_MS
        val FADE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

/** Every colour a new surface needs. [palette] in composition; [live] only in draw-phase lambdas. */
@Stable
class CosmoColors internal constructor(private val state: CosmoPaletteState) {
    val palette: CoverColors get() = state.target
    fun live(field: Int): Color = Color(state.liveArgb(field))
    /** Whether the theme the palette is heading for is the dark one (for the grain opacity). */
    val dark: Boolean get() = state.targetDark
}

@Stable
class CosmoShellState internal constructor(val settings: CosmoLookSettings, dark: Boolean) {
    val palette = CosmoPaletteState(CosmoBrandPalettes.of(dark), dark)
    val colors = CosmoColors(palette)

    /** The cover of the song that is playing, and of the one after it: set by [FollowCover], read by the palette follower. */
    internal var coverUrl by mutableStateOf<String?>(null)
    internal var nextCoverUrl by mutableStateOf<String?>(null)

    /** The playing song's album, to let two tracks of one album differ more before the app re-themes. */
    internal var coverAlbum by mutableStateOf<String?>(null)
}

/** The palette of a cover with no colour: Cosmotune's own apricot (01-dynamic-colour.md section 3, rule 6). */
object CosmoBrandPalettes {
    private val darkBrand by lazy { CoverPalette.fromPixels(IntArray(0), 0, 0, dark = true) }
    private val lightBrand by lazy { CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false) }
    fun of(dark: Boolean): CoverColors = if (dark) darkBrand else lightBrand
}
