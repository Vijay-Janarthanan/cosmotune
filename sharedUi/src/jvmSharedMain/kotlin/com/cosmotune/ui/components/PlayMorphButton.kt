package com.cosmotune.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.design.LoadingBlob
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.pause
import com.cosmotune.music.sharedui.resources.play
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The play / pause button. In the page ([floating] false) it is the hero of the screen: the strong cover gradient
 * (`buttonGradientStart` to `buttonGradientEnd` at 145 degrees), a rounded square (0.32 of its size) while paused that
 * morphs to a circle while playing, squares up to 0.20 and scales to 0.92 when pressed, on the press spring. On a floating
 * layer (the mini player, Now Playing over video) it is the C look: 72 % cover colour over the glass, a circle while paused
 * and 0.32 while playing, a 1.5 dp light ring and a 2 dp highlight on top.
 *
 * [loading] swaps the icon for a blob that turns into a circle and back (1.1 s, 0.32 of the size); on a floating layer it
 * is a circle with a 3 dp ink arc turning 6 dp outside it. Reduced motion: no scale, a still shape. The content
 * description is "Play" or "Pause". A button under 48 dp reserves a 48 dp touch target.
 */
@Composable
fun PlayMorphButton(
    playing: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    enabled: Boolean = true,
    floating: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val ink = Color(colors.palette.onAccent)
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val label = stringResource(if (playing) Res.string.pause else Res.string.play)
    // The C loading state is a circle with an orbiting arc; only then does anything turn here.
    val arcSpin: State<Float>? = if (loading && floating && !reduced) {
        rememberInfiniteTransition(label = "play-arc").animateFloat(0f, 360f, infiniteRepeatable(tween(ARC_PERIOD_MS, easing = LinearEasing)), label = "play-arc-angle")
    } else null
    MorphBox(
        targetFor = { pressed -> ControlMorph.playButton(size.value, playing && !(floating && loading), pressed, floating) },
        onClick = { haptics.play(if (playing) Haptic.Pause else Haptic.Resume); onClick() },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .semantics { contentDescription = label },
        enabled = enabled,
        floating = floating,
        stateLayer = ink,
        shadow = if (floating) null else { outline -> softShadow(outline, Color.Black.copy(alpha = HERO_SHADOW_ALPHA), HERO_SHADOW_OFFSET_DP, HERO_SHADOW_BLUR_DP) },
        edge = { outline -> playEdge(outline, floating, loading, arcSpin?.value ?: 0f, colors) },
        fill = { if (floating) floatingPlayFill(colors) else heroFill(colors) },
    ) {
        when {
            loading && floating -> Unit   // the ring outside is the whole indicator (components-v3bc.css, `.play2.is-loading .ic`)
            loading -> LoadingBlobShape(ControlMetrics.playBlobDp(size.value).dp, ink, reduced)
            else -> Icon(
                if (playing) CosmoIcons.Pause else CosmoIcons.Play,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(ControlMetrics.playIconDp(size.value).dp),
            )
        }
    }
}

/** The hero's drop shadow, `0 1px 2px rgb(0 0 0 / .22)`. */
private const val HERO_SHADOW_ALPHA = 0.22f
private const val HERO_SHADOW_OFFSET_DP = 1f
private const val HERO_SHADOW_BLUR_DP = 2f
private const val ARC_PERIOD_MS = 1000

private fun DrawScope.playEdge(outline: Path, floating: Boolean, loading: Boolean, arcAngle: Float, c: CosmoColors) {
    if (!floating) {
        topLine(outline, Color.White.copy(alpha = ControlMetrics.HIGHLIGHT_PLAY))
        return
    }
    innerRing(outline, c.floatingPlayRing(), ControlMetrics.C_PLAY_RING_DP)
    topLine(outline, Color.White.copy(alpha = ControlMetrics.C_PLAY_TOP_HIGHLIGHT), ControlMetrics.C_PLAY_TOP_HIGHLIGHT_DP)
    if (loading) {
        // `border: 3px`, `inset: -6px`: the ring's outer edge is 6 dp outside the button, so its centre line is 4.5 dp out
        val width = ControlMetrics.C_PLAY_ARC_DP.dp.toPx()
        val out = ControlMetrics.C_PLAY_ARC_GAP_DP.dp.toPx() - width / 2f
        drawArc(
            color = c.ink(1f), startAngle = arcAngle - 135f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(-out, -out), size = Size(size.width + 2f * out, size.height + 2f * out), style = Stroke(width),
        )
    }
}

/** The loading blob: one path refilled every frame, turned and scaled in the draw phase. */
@Composable
internal fun LoadingBlobShape(size: Dp, color: Color, reduced: Boolean) {
    val phase: State<Float>? = if (reduced) null else {
        rememberInfiniteTransition(label = "blob").animateFloat(0f, 1f, infiniteRepeatable(tween(LoadingBlob.PERIOD_MS, easing = LinearEasing)), label = "blob-phase")
    }
    val frame = remember { FloatArray(LoadingBlob.SIZE) }
    val path = remember { Path() }
    Canvas(Modifier.size(size)) {
        LoadingBlob.at(phase?.value ?: 0.5f, frame)   // reduced motion: the still circle
        fillBlob(path, this.size.width, this.size.height, frame)
        rotate(frame[LoadingBlob.ROTATION_DEG], center) { scale(frame[LoadingBlob.SCALE], center) { drawPath(path, color) } }
    }
}

/** A rectangle whose corners are ellipses of the given fractions of its sides (CSS `border-radius: a b c d / e f g h`). */
private fun fillBlob(path: Path, w: Float, h: Float, f: FloatArray) {
    val r = LoadingBlob.RADII
    val tlx = f[r] * w; val trx = f[r + 1] * w; val brx = f[r + 2] * w; val blx = f[r + 3] * w
    val tly = f[r + 4] * h; val try_ = f[r + 5] * h; val bry = f[r + 6] * h; val bly = f[r + 7] * h
    path.reset()
    path.moveTo(tlx, 0f)
    path.lineTo(w - trx, 0f)
    path.arcTo(Rect(w - 2f * trx, 0f, w, 2f * try_), 270f, 90f, false)
    path.lineTo(w, h - bry)
    path.arcTo(Rect(w - 2f * brx, h - 2f * bry, w, h), 0f, 90f, false)
    path.lineTo(blx, h)
    path.arcTo(Rect(0f, h - 2f * bly, 2f * blx, h), 90f, 90f, false)
    path.lineTo(0f, tly)
    path.arcTo(Rect(0f, 0f, 2f * tlx, 2f * tly), 180f, 90f, false)
    path.close()
}
