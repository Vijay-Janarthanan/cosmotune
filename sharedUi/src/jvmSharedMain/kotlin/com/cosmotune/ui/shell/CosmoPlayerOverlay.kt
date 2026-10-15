package com.cosmotune.ui.shell

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cosmotune.design.Morph
import com.cosmotune.design.ShellMetrics
import com.cosmotune.design.Springs
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.MorphPath
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.ui.player.PlayerBackHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Now Playing as a layer over the whole window that grows out of the mini capsule (`V2.morph`): one progress value,
 * [CosmoShellState.playerProgress], runs from 0 (the capsule's rectangle and corners) to 1 (the window), on the move spring.
 * While it runs, the layer clips itself to that rectangle and fills it with the ground colour, the player's [content] fades in
 * over the last fifth, the capsule's cover travels to the player's cover rectangle ([CosmoShellState.playerCoverBounds]) and
 * the capsule's title and artist fade out in the first half ([MorphLayer]).
 *
 * The same value is the finger's: a drag down on the open player takes it back towards the capsule, and letting go below
 * three quarters (or fast) closes it through [onDismiss]. With reduced motion there is no travelling and no clip: the
 * player fades in and out over 150 ms.
 *
 * [content] gets the window's size and is composed only while the layer is on screen. The layer provides
 * [LocalCosmoPlayerDismiss] around it, which closes the player. Nothing here allocates while the player stands open or
 * closed; a frame of the morph refills two paths and allocates nothing else.
 */
@Composable
fun CosmoPlayerOverlay(
    open: Boolean,
    onDismiss: () -> Unit,
    shell: CosmoShellState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(windowWidth: Dp, windowHeight: Dp) -> Unit,
) {
    val progress = shell.playerProgress
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    // Whatever the caller's lambda is now, through one stable function: the local below is static, and a new value
    // would recompose the whole player each time the caller does.
    val dismissNow by rememberUpdatedState(onDismiss)
    val dismiss = remember { { dismissNow() } }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(open) {
        // The move spring overshoots (damping 0.8). The progress is a fraction of the way to the window, so it stops at its
        // ends: the animation then ends the moment the card reaches the capsule or the window, not a settle later, and the
        // capsule is handed back (and the layer put away) in step with the card.
        progress.updateBounds(0f, 1f)
        val remounted = firstRun[0]
        firstRun[0] = false
        if (!open && remounted && progress.value > 0f) {
            // Left half-way by an earlier life of this layer (the song went away while the player was up): do not play
            // the player closing over a screen that has long since closed it.
            progress.snapTo(0f)
            shell.capsuleHidden = false
            return@LaunchedEffect
        }
        // With reduced motion the player fades over the capsule, which stays where it is.
        if (open && !reduced) shell.capsuleHidden = true
        progress.animateTo(
            if (open) 1f else 0f,
            if (reduced) tween(Springs.REDUCED_MS) else spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness, visibilityThreshold = 0.001f),
        )
        if (!open) shell.capsuleHidden = false
    }
    // The layer stays until the capsule is shown again, so that no frame has neither the card nor the capsule.
    val visible by remember(open) { derivedStateOf { open || progress.value > 0f || shell.capsuleHidden } }
    if (!visible) return
    PlayerBackHandler(enabled = open, onBack = dismiss)   // the player's own handlers are composed later and win
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val capsuleRadiusPx = with(density) { ShellMetrics.CAPSULE_RADIUS_DP.dp.toPx() }
        val morph = remember { MorphPath() }
        val clip = remember { Path() }
        val ground = LocalCosmoColors.current
        // Where this layer sits in the window: the capsule's bounds are in root coordinates. Read when drawing, never composed.
        val origin = remember { floatArrayOf(0f, 0f) }
        // Each step of a drag is applied before the next call is made, and before the release below: UNDISPATCHED puts a
        // step in the animation's queue in order, so a late step can never cut short the settle that follows the release.
        val dragState = rememberDraggableState { dy ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo((progress.value - dy / heightPx).coerceIn(0f, 1f)) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (reduced) progress.value else 1f }
                .onGloballyPositioned { origin[0] = it.positionInRoot().x; origin[1] = it.positionInRoot().y }
                .draggable(dragState, Orientation.Vertical, enabled = open, onDragStopped = { velocity ->
                    if (progress.value < DISMISS_BELOW || velocity > DISMISS_FLING_PX_PER_S) dismiss() else progress.animateTo(1f, CosmoMotion.move(reduced))
                })
                .drawWithContent {
                    val t = if (reduced) 1f else progress.value
                    // The card exists while the player has left the capsule or the capsule is hidden (so the frame that
                    // swaps the one for the other shows exactly one of them).
                    if (t <= 0f && !shell.capsuleHidden) return@drawWithContent
                    val groundColor = ground.live(CoverField.BACKGROUND_BOTTOM)   // the card's ground while it grows
                    if (t >= 1f) {
                        drawRect(groundColor)
                        this@drawWithContent.drawContent()
                        return@drawWithContent
                    }
                    val capsule = shell.capsuleBounds
                    val fromLeft = (capsule?.left ?: 0f) - origin[0]
                    val fromTop = (capsule?.top ?: size.height) - origin[1]
                    val fromRight = (capsule?.right ?: size.width) - origin[0]
                    val fromBottom = (capsule?.bottom ?: size.height) - origin[1]
                    val left = Morph.lerp(fromLeft, 0f, t)
                    val top = Morph.lerp(fromTop, 0f, t)
                    val right = Morph.lerp(fromRight, size.width, t)
                    val bottom = Morph.lerp(fromBottom, size.height, t)
                    // The capsule's own superellipse corners, run down to square. MorphPath refills one path in place, so a
                    // frame of the morph allocates nothing.
                    val outline = morph.update(right - left, bottom - top, Morph.cornerRadius(t, capsuleRadiusPx), CAPSULE_EXPONENT)
                    clip.reset()
                    clip.addPath(outline, Offset(left, top))
                    clipPath(clip) {
                        drawRect(groundColor)
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (reduced) 1f else Morph.playerContentAlpha(progress.value) }) {
                CompositionLocalProvider(LocalCosmoPlayerDismiss provides dismiss) { content(w, h) }
            }
            // The travelling cover and text: only while the card is between the capsule and the window.
            val morphing by remember(reduced) { derivedStateOf { !reduced && shell.capsuleHidden && progress.value < 1f } }
            if (morphing) MorphLayer(shell, widthPx, heightPx, origin)
        }
    }
}

/**
 * The capsule's cover, flying to where the player's cover will be, and the capsule's title and artist, fading out as it
 * goes. The cover is laid out once at the size of the player's cover (its rectangle in [CosmoShellState.playerCoverBounds], else
 * 320 dp centred, 112 dp from the top), so the picture is sharp when it lands, and is carried there while it is drawn: a
 * translation from the capsule's cover, a scale from 48 dp up to 1, and a clip whose corners run from 14 dp to 28 dp on screen.
 *
 * Everything here reads the progress in the draw phase, as the card does ([CosmoPlayerOverlay]), and not in a `graphicsLayer`
 * block: a layer block is applied after the draw lambdas of the same frame (seen in a headless render, where the cover lagged the
 * card by a frame, so the card's edge cut the cover's corner on the way back), and two things that must move together are
 * drawn by the same phase. The clip is one [MorphPath] refilled in place, so a frame of the morph allocates nothing here.
 */
@Composable
private fun MorphLayer(shell: CosmoShellState, windowWidth: Float, windowHeight: Float, origin: FloatArray) {
    val density = LocalDensity.current
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val progress = shell.playerProgress
    val label = shell.capsuleLabel
    val capsule = shell.capsuleBounds
    val target = shell.playerCoverBounds
    val coverSize: Dp = if (target != null) with(density) { target.width.toDp() } else FALLBACK_COVER
    val startSize = with(density) { COVER_SIZE.toPx() }
    val padding = with(density) { CAPSULE_PADDING.toPx() }
    val fallbackTop = with(density) { FALLBACK_COVER_TOP.toPx() }
    val miniRadius = with(density) { CosmoRadii.coverMini.toPx() }
    val playerRadius = with(density) { CosmoRadii.coverNowPlaying.toPx() }
    val clip = remember { MorphPath() }
    CoverImage(
        url = label?.coverUrl ?: shell.coverUrl,
        size = coverSize,
        radius = CosmoRadii.coverNowPlaying,
        shadow = false,
        modifier = Modifier.drawWithContent {
            val t = progress.value
            val startScale = startSize / size.width
            val scale = Morph.lerp(startScale, 1f, t)
            val fromX = (capsule?.left ?: 0f) - origin[0] + padding
            val fromY = (capsule?.top ?: windowHeight) - origin[1] + padding
            val toX = if (target != null) target.left - origin[0] else (windowWidth - size.width) / 2f
            val toY = if (target != null) target.top - origin[1] else fallbackTop
            // The radius is in the cover's own (unscaled) space: 14 dp on screen at the start, 28 dp at the end.
            val radius = Morph.lerp(miniRadius / startScale, playerRadius, t)
            translate(Morph.lerp(fromX, toX, t), Morph.lerp(fromY, toY, t)) {
                scale(scale, scale, pivot = Offset.Zero) {
                    clipPath(clip.update(size.width, size.height, radius, CAPSULE_EXPONENT)) { this@drawWithContent.drawContent() }
                }
            }
        },
    )
    if (label != null) {
        val textStart = with(density) { CAPSULE_TEXT_START.toPx() }
        val textWidth = with(density) { ((capsule?.width ?: windowWidth) - textStart - CAPSULE_TEXT_END.toPx()).coerceAtLeast(0f).toDp() }
        val title = Color(palette.textPrimary)
        val artist = Color(palette.textSecondary)
        Column(
            Modifier
                .offset { IntOffset(((capsule?.left ?: 0f) - origin[0] + textStart).roundToInt(), ((capsule?.top ?: windowHeight) - origin[1]).roundToInt()) }
                .size(textWidth, ShellMetrics.CAPSULE_HEIGHT_DP.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // The colour is produced when the text is drawn, so its fade (gone by the time the card is half-way) is of the card's frame.
            BasicText(label.title, style = capsuleTitleStyle(type.textFamily), maxLines = 1, overflow = TextOverflow.Ellipsis, color = { title.copy(alpha = Morph.capsuleTextAlpha(progress.value)) })
            BasicText(label.artist, style = capsuleArtistStyle(type.textFamily), maxLines = 1, overflow = TextOverflow.Ellipsis, color = { artist.copy(alpha = Morph.capsuleTextAlpha(progress.value)) })
        }
    }
}

private const val CAPSULE_EXPONENT = 4f
private const val DISMISS_BELOW = 0.75f
private const val DISMISS_FLING_PX_PER_S = 1600f
private val CAPSULE_PADDING = 8.dp
private val FALLBACK_COVER = 320.dp
private val FALLBACK_COVER_TOP = 112.dp
