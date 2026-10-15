package com.cosmotune.ui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.LoadingBlob
import com.cosmotune.design.PullIndicator
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.keepRedrawnWith
import com.cosmotune.ui.components.liftShadow
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_refreshing
import kotlin.math.max
import org.jetbrains.compose.resources.stringResource

/**
 * The pull-to-refresh indicator (`.ptr` of the B+C spec): a 44 dp tonal circle with a soft shadow, an 18 dp blob in `accent`
 * inside it. While the list is pulled ([fraction], 0 at rest, 1 at the threshold) the blob grows, turns and squares up
 * ([PullIndicator]); while [refreshing] it runs the loading loop ([LoadingBlob], 1.1 s), and under reduced motion it is a
 * still circle. The circle starts at the bottom edge of the bar and moves down by `fraction x 64 dp`, reluctantly past 1,
 * like the list under it; it is not drawn while the fraction is 0 and nothing refreshes.
 *
 * It lays itself out at zero height and is as wide as its parent: the bar keeps its size, the circle hangs over the page
 * and takes no touches from it. [fraction] is read in `graphicsLayer` and in draw only, so a drag recomposes nothing.
 */
@Composable
fun CosmoPullIndicator(fraction: () -> Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val currentFraction by rememberUpdatedState(fraction)
    // A refresh that starts without a pull (a retry button) still brings the circle down, and its end takes it back up.
    val held = remember { Animatable(if (refreshing) 1f else 0f) }
    LaunchedEffect(refreshing, reduced) { held.animateTo(if (refreshing) 1f else 0f, CosmoMotion.move(reduced)) }
    val visible by remember { derivedStateOf { currentFraction() > 0f || held.value > 0f } }
    if (!refreshing && !visible) return

    val label = stringResource(Res.string.cosmo_refreshing)
    val phase: State<Float>? = if (refreshing && !reduced) {
        rememberInfiniteTransition(label = "pullBlob").animateFloat(0f, 1f, infiniteRepeatable(tween(LoadingBlob.PERIOD_MS, easing = LinearEasing)), label = "pullBlobPhase")
    } else null
    val frame = remember { FloatArray(LoadingBlob.SIZE) }
    val path = remember { Path() }

    Box(
        modifier
            // Zero height: the bar keeps its size; the circle overhangs the page.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                layout(placeable.width, 0) { placeable.place(0, 0) }
            }
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val f = max(currentFraction(), held.value).coerceIn(0f, MAX_FRACTION)
                    // Past the threshold it keeps following, but reluctantly, like the list under it.
                    val eased = if (f <= 1f) f else 1f + (f - 1f) * PAST_THRESHOLD
                    translationY = eased * PullIndicator.TRAVEL_DP.dp.toPx()
                    alpha = (f * FADE_IN_SPEED).coerceAtMost(1f)
                }
                // `--e2`: the soft lift of a raised disc, 6 dp.
                .liftShadow(CircleShape, 6.dp, spot = { Color.Black.copy(alpha = 0.26f) }, ambient = { Color.Black.copy(alpha = 0.10f) })
                .size(PullIndicator.SIZE_DP.dp)
                .semantics { if (refreshing) { contentDescription = label; liveRegion = LiveRegionMode.Polite } }
                .drawBehind {
                    keepRedrawnWith(palette)
                    drawCircle(tonalFill(colors))
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(PullIndicator.BLOB_DP.dp)) {
                keepRedrawnWith(palette)
                val accent = colors.live(CoverField.ACCENT)
                if (refreshing) {
                    // The loading loop; with reduced motion the circle it passes through half way, standing still.
                    LoadingBlob.at(phase?.value ?: 0.5f, frame)
                    fillBlob(path, size.width, size.height, frame)
                    rotate(frame[LoadingBlob.ROTATION_DEG], center) { scale(frame[LoadingBlob.SCALE], center) { drawPath(path, accent) } }
                } else {
                    val f = currentFraction()
                    rotate(PullIndicator.rotationDeg(f), center) {
                        scale(PullIndicator.scale(f), center) {
                            drawRoundRect(accent, cornerRadius = CornerRadius(PullIndicator.cornerFraction(f) * size.minDimension))
                        }
                    }
                }
            }
        }
    }
}

/** A pull this far past the threshold is as far as the circle goes (it has travelled 1.35 times the distance). */
private const val MAX_FRACTION = 2f

/** How much of the distance past the threshold the circle follows. */
private const val PAST_THRESHOLD = 0.35f

/** The circle is fully there at a third of the pull. */
private const val FADE_IN_SPEED = 3f

/** A rectangle whose corners are ellipses of the given fractions of its sides (CSS `border-radius: a b c d / e f g h`), refilled each frame into one path. */
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
