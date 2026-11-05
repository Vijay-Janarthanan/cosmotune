package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.cosmotune.music.ui.graphics.imageBitmapOf

/**
 * Film grain over the ambient ground: it breaks up the 8-bit banding of the slow gradients (`--grain`, `--grain-op` in
 * `tokens-v3bc.css`). Draw it once, over the ground and under the content, never over text, covers or video.
 *
 * It is per-pixel white noise from a 128 x 128 tile, not the fractal noise of the designer's SVG filter; both do the job
 * of hiding banding, and the tile costs one cached bitmap and one draw call.
 *
 * Hook for `AmbientBackground` (`cosmoAmbientGround`): after the two glows, with `val grain = rememberGrainBrush()` read
 * in composition and `dark` from `LocalCosmoShell.current?.palette?.targetDark ?: true`, add the one line
 * `drawGrain(grain, dark)`. A caller with no ground of its own can use `Modifier.cosmoGrain(dark)` instead.
 */
object Grain {
    const val SIZE = 128
    const val ALPHA_DARK = 0.045f
    const val ALPHA_LIGHT = 0.03f

    /** The first value of the sequence is made from this seed, so the tile is the same on every run and every platform. */
    private const val SEED = 1

    fun alpha(dark: Boolean): Float = if (dark) ALPHA_DARK else ALPHA_LIGHT

    /**
     * [SIZE] x [SIZE] opaque grey pixels from a fixed linear congruential sequence: `seed = seed * 1103515245 + 12345`
     * (32-bit, wrapping), value `(seed ushr 16) and 0xFF`.
     */
    fun pixels(): IntArray {
        var seed = SEED
        return IntArray(SIZE * SIZE) {
            seed = seed * 1103515245 + 12345
            val v = (seed ushr 16) and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** The tile, built once per process. */
    val tile: ImageBitmap by lazy { imageBitmapOf(pixels(), SIZE, SIZE) }
}

/** The grain as a repeating shader brush. One per composition; the bitmap behind it is shared. */
@Composable
fun rememberGrainBrush(): Brush = remember { ShaderBrush(ImageShader(Grain.tile, TileMode.Repeated, TileMode.Repeated)) }

/** One draw call: the grain over the whole drawing area, blended with Overlay at the theme's grain opacity. */
fun DrawScope.drawGrain(grain: Brush, dark: Boolean) {
    drawRect(grain, alpha = Grain.alpha(dark), blendMode = BlendMode.Overlay)
}

/** The grain behind a node's content, for a ground that is not drawn by `cosmoAmbientGround`. */
@Composable
fun Modifier.cosmoGrain(dark: Boolean): Modifier {
    val grain = rememberGrainBrush()
    return drawBehind { drawGrain(grain, dark) }
}
