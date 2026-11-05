package com.cosmotune.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.LoadingBlob
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.OneMotion
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.pause
import com.cosmotune.music.sharedui.resources.play
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The play / pause button (DESIGN_SYSTEM.md 4.12, 4.18): in the page a plain circle in the accent with its ink, and on a [floating]
 * layer (the mini player, the player bar) just the glyph in the primary ink. It never changes shape: the glyph cross-fades, 150 ms
 * (at once under reduced motion). [loading] puts a spinner in the glyph's place. The content description is "Play" or "Pause". A
 * button under 48 dp reserves a 48 dp touch target.
 */
@Composable
fun PlayMorphButton(
    playing: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    enabled: Boolean = true,
    floating: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val ink = Color(if (floating) colors.palette.textPrimary else colors.palette.onAccent)
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val label = stringResource(if (playing) Res.string.pause else Res.string.play)
    val interaction = remember { MutableInteractionSource() }
    val glyph = size * if (floating) 0.6f else 0.5f
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .cosmoFocusRing(interaction, size / 2)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .then(if (floating) Modifier else Modifier.background(Color(colors.palette.accent)))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button) {
                haptics.play(if (playing) Haptic.Pause else Haptic.Resume)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(glyph * 0.8f), color = ink, strokeWidth = 2.5.dp)
        } else {
            Crossfade(playing, animationSpec = tween(if (reduced) 0 else OneMotion.SHORT_MS), label = "play-glyph") { showsPause ->
                Icon(
                    if (showsPause) CosmoIcons.Pause else CosmoIcons.Play,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(glyph),
                )
            }
        }
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
