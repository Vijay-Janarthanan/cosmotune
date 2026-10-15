package com.cosmotune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.design.MorphTarget
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** The opacity of a control's state layer (its label colour laid over it) while pressed and while hovered (desktop). */
internal const val STATE_LAYER_PRESSED = 0.12f
internal const val STATE_LAYER_HOVERED = 0.08f

/** How far a disabled control fades: its content and fill are drawn at 38 %. */
internal const val DISABLED_ALPHA = 0.38f

/**
 * The surface every morphing control draws with: a superellipse that animates its corner radius, its exponent and its scale
 * towards [targetFor] on the press spring (`CosmoMotion.press`), so a press and a state change interrupt each other and keep
 * their velocity. One [Animatable] per number, read only in draw and graphicsLayer lambdas: a press recomposes the box twice
 * (down, up) and never per frame.
 *
 * The colours are drawn in [fill] (the surface), [shadow] (under it) and [edge] (over it, for highlights and borders that
 * must follow the morphing outline: they get the shape as a [Path]). These lambdas run in the draw phase and must read
 * `LocalCosmoColors.current.live(...)` every time they run, never a brush made once: the designer found that a brush cached
 * at theme creation makes every control the fallback apricot.
 *
 * [stateLayer] is laid over the fill, clipped to the outline, at [pressedLayerAlpha] while pressed and
 * [STATE_LAYER_HOVERED] under a mouse; [Color.Unspecified] draws none. Reduced motion: no scale, and every spring becomes a
 * 150 ms fade. A disabled control fades to 38 % ([dimWhenDisabled] false lets the caller draw its own disabled look).
 * [floating] only picks the focus ring: the 2 dp ink ring of the C recipe over the B ring of `accentStrong`. [lift] is a platform
 * elevation shadow drawn in the same layer as the press scale (a tile's lift, `--e1`), so it shrinks with the press.
 */
@Composable
internal fun MorphBox(
    targetFor: (pressed: Boolean) -> MorphTarget,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    toggled: Boolean? = null,
    role: Role = Role.Button,
    floating: Boolean = false,
    dimWhenDisabled: Boolean = true,
    stateLayer: Color = Color.Unspecified,
    pressedLayerAlpha: Float = STATE_LAYER_PRESSED,
    shadow: (DrawScope.(outline: Path) -> Unit)? = null,
    lift: Lift? = null,
    edge: (DrawScope.(outline: Path) -> Unit)? = null,
    fill: DrawScope.() -> Brush?,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // Read in composition: a cover change (once per cover) recomposes the box, and the draw lambda below captures it, so a
    // palette that arrives without a fade frame (the first cover, which snaps) still redraws the control.
    val palette = LocalCosmoColors.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val target = targetFor(pressed && enabled)
    val radius = remember { Animatable(target.radiusDp) }
    val exponent = remember { Animatable(target.exponent) }
    val scaleX = remember { Animatable(if (reduced) 1f else target.scale) }
    val scaleY = remember { Animatable(if (reduced) 1f else target.scaleY) }
    val layer = remember { Animatable(0f) }
    val layerTarget = if (!enabled) 0f else if (pressed) pressedLayerAlpha else if (hovered) STATE_LAYER_HOVERED else 0f
    LaunchedEffect(target, reduced) {
        launch { radius.animateTo(target.radiusDp, CosmoMotion.press(reduced)) }
        launch { exponent.animateTo(target.exponent, CosmoMotion.press(reduced)) }
        launch { scaleX.animateTo(if (reduced) 1f else target.scale, CosmoMotion.press(reduced)) }
        launch { scaleY.animateTo(if (reduced) 1f else target.scaleY, CosmoMotion.press(reduced)) }
    }
    LaunchedEffect(layerTarget, reduced) { layer.animateTo(layerTarget, CosmoMotion.effects(reduced)) }
    val morph = remember { MorphPath() }
    val action = when {
        onClick == null -> Modifier
        toggled != null -> Modifier.toggleable(toggled, interaction, indication = null, enabled = enabled, role = role) { onClick() }
        else -> Modifier.clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
    Box(
        modifier
            .graphicsLayer {
                this.scaleX = scaleX.value
                this.scaleY = scaleY.value
                alpha = if (enabled || !dimWhenDisabled) 1f else DISABLED_ALPHA
                if (lift != null) applyLift(lift)
            }
            .cosmoFocusRing(interaction, target.radiusDp.dp, floating)
            .drawBehind {
                keepRedrawnWith(palette)
                val outline = morph.update(size.width, size.height, radius.value.dp.toPx(), exponent.value)
                shadow?.invoke(this, outline)
                fill()?.let { drawPath(outline, it) }
                if (stateLayer != Color.Unspecified && layer.value > 0.001f) drawPath(outline, stateLayer.copy(alpha = stateLayer.alpha * layer.value))
                edge?.invoke(this, outline)
            }
            .hoverable(interaction, enabled)
            .then(action),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Naming the palette inside the draw lambda makes the lambda capture it: a new palette is a new lambda, hence a redraw. */
@Suppress("UNUSED_PARAMETER")
internal fun keepRedrawnWith(palette: CoverColors) = Unit
