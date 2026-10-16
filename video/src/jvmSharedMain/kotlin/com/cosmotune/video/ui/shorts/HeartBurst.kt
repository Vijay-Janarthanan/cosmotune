package com.cosmotune.video.ui.shorts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cosmotune.design.Springs
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.design.VideoMetrics
import kotlin.math.roundToInt

/** One heart burst: where it is centred (pixels from the page's top-left corner) and its serial, so two bursts in a row both play. */
@Immutable
internal data class HeartBurstSpec(val x: Float, val y: Float, val serial: Int)

/**
 * The heart of a double tap (spec 5.15): `CosmoIcons.HeartFill` at [VideoMetrics.HEART_BURST_DP] in `accent`, centred on the tap,
 * growing from 0.6 to 1.2 and settling at 1 while it fades in and out over [VideoMetrics.HEART_BURST_MS]; with reduced motion a
 * plain [Springs.REDUCED_MS] fade. The scale and the fade are read in the layer block, so the animation recomposes nothing.
 * [onDone] is called when it has played. Decoration: the like button says the state.
 */
@Composable
internal fun HeartBurst(burst: HeartBurstSpec, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val accent = Color(LocalCosmoColors.current.palette.accent)
    val done by rememberUpdatedState(onDone)
    val progress = remember(burst.serial) { Animatable(0f) }
    LaunchedEffect(burst.serial) {
        progress.animateTo(1f, tween(if (reduced) Springs.REDUCED_MS else VideoMetrics.HEART_BURST_MS, easing = LinearEasing))
        done()
    }
    Icon(
        CosmoIcons.HeartFill,
        contentDescription = null,
        tint = accent,
        modifier = modifier
            .offset {
                val half = (VideoMetrics.HEART_BURST_DP / 2).dp.toPx()
                IntOffset((burst.x - half).roundToInt(), (burst.y - half).roundToInt())
            }
            .size(VideoMetrics.HEART_BURST_DP.dp)
            .graphicsLayer {
                val t = progress.value
                val scale = HeartBurstCurve.scale(t, reduced)
                scaleX = scale
                scaleY = scale
                alpha = HeartBurstCurve.alpha(t, reduced)
            },
    )
}

/**
 * The burst's curve over its time [t] (0 to 1): the scale goes from 0.6 to 1.2 in the first 40 %, back to 1 by 60 %, and stays; the
 * heart fades in over the first 15 % and out over the last 40 %. Reduced motion: no scale, a fade out over the whole (short) time.
 */
internal object HeartBurstCurve {
    private const val GROW_END = 0.40f
    private const val SETTLE_END = 0.60f
    private const val FADE_IN_END = 0.15f
    private const val FADE_OUT_START = 0.60f

    fun scale(t: Float, reduced: Boolean): Float = when {
        reduced -> 1f
        t < GROW_END -> lerp(0.6f, 1.2f, t / GROW_END)
        t < SETTLE_END -> lerp(1.2f, 1f, (t - GROW_END) / (SETTLE_END - GROW_END))
        else -> 1f
    }

    fun alpha(t: Float, reduced: Boolean): Float = when {
        reduced -> (1f - t).coerceIn(0f, 1f)
        t < FADE_IN_END -> t / FADE_IN_END
        t < FADE_OUT_START -> 1f
        else -> ((1f - t) / (1f - FADE_OUT_START)).coerceIn(0f, 1f)
    }

    private fun lerp(from: Float, to: Float, f: Float): Float = from + (to - from) * f.coerceIn(0f, 1f)
}
