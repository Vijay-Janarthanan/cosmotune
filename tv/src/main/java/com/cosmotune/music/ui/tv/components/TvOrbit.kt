package com.cosmotune.music.ui.tv.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.theme.TvGlass
import kotlinx.coroutines.launch

/*
 * Focus on the TV in Orbit (board batch-be section E): a thick peach ring a little outside whatever has focus, the ground showing
 * between the two. Every TV piece that takes focus shows it this way: the TV's own lifted pieces through [tvLift], the kit's chips
 * and buttons through [tvFocusRing], and anything else clickable (Watch's pages) through [TvFocusIndication].
 */

/** The ring's thickness and its distance from the thing in focus. */
internal val RingWidth = 4.dp
internal val RingGap = 4.dp

/** Draws the focus ring of [shape] round the current bounds, [gap] outside them, at [alpha]. */
internal fun DrawScope.drawFocusRing(shape: Shape, alpha: Float, gap: Dp = RingGap, width: Dp = RingWidth) {
    if (alpha <= 0f) return
    val w = width.toPx()
    val grow = gap.toPx() + w / 2f
    val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
    translate(-grow, -grow) { drawOutline(outline, TvGlass.Peach.copy(alpha = alpha), style = Stroke(w)) }
}

/**
 * The ring round a kit piece that takes focus inside itself (an [com.cosmotune.ui.orbit.OrbitPlayButton], a chip): put it in the
 * piece's modifier, before its own clip. [onFocus] hears focus arrive and leave.
 */
@Composable
fun Modifier.tvFocusRing(shape: Shape, gap: Dp = RingGap, onFocus: ((Boolean) -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "ring")
    val chrome = LocalTvChrome.current
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged {
        if (it.hasFocus != focused) {
            focused = it.hasFocus
            if (it.hasFocus) chrome.lastFocused = requester
            onFocus?.invoke(it.hasFocus)
        }
    }.drawBehind { drawFocusRing(shape, alpha, gap) }
}

/**
 * The indication for anything clickable that is not one of the TV's own pieces: the peach ring just inside its edge while it has
 * focus (the shape is not known here, so the ring rounds its corners up to a pill). No press ripple: on a TV the ring is the answer.
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = RingNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 7

    private class RingNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var focus = 0
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> focus++
                        is FocusInteraction.Unfocus -> focus--
                        else -> return@collect
                    }
                    launch { alpha.animateTo(if (focus > 0) 1f else 0f, tween(120)) { invalidateDraw() } }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val a = alpha.value
            if (a <= 0f) return
            val w = RingWidth.toPx()
            val r = minOf(size.minDimension / 2f, 30.dp.toPx())
            drawRoundRect(
                TvGlass.Peach.copy(alpha = a),
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(r - w / 2f),
                style = Stroke(w),
            )
        }
    }
}
