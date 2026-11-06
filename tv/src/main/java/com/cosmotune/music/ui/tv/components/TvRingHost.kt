package com.cosmotune.music.ui.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.theme.TvGlass

/*
 * The TV's focus ring for pages it did not draw (Watch's shared screens). Their pieces clip themselves before they click, so an
 * indication can only draw inside them; [tvRingHost] draws the TV's thick peach ring a little outside whatever has focus beneath it
 * instead, from the focused piece's bounds, over everything around it, as [tvLift] does for the TV's own pieces.
 */

/**
 * Draws the TV focus ring ([RingWidth] thick, [RingGap] out) round the focused piece anywhere inside this box, its corners rounded to
 * half the piece's shorter side up to [cornerCap] (cards and rows carry a radius of their own, chips and round buttons are pills).
 * [TvFocusIndication] beneath it then draws nothing, so a piece is never ringed twice; inside a popup (a menu) it still draws.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvRingHost(cornerCap: Dp): Modifier {
    val ring = remember { RingTarget() }
    val alpha by animateFloatAsState(if (ring.shown) 1f else 0f, tween(120), label = "tvRingHost")
    return this
        .then(RingHostElement)
        .onPlaced { ring.host = it }
        // Whether focus is anywhere inside: the bounds below say where, but a piece losing focus reports null in no fixed order
        // with the next one reporting itself, so the ring goes only when focus leaves the box.
        .onFocusChanged { ring.shown = it.hasFocus && ring.focused != null }
        .onFocusedBoundsChanged { coordinates ->
            if (coordinates != null) {
                ring.focused = coordinates
                ring.shown = true
                ring.moves++
            }
        }
        .drawWithContent {
            drawContent()
            ring.moves
            val host = ring.host
            val focused = ring.focused
            val rect = if (host != null && focused != null && host.isAttached && focused.isAttached) {
                host.localBoundingBoxOf(focused, clipBounds = false).also { ring.last = it }
            } else ring.last
            val a = alpha
            if (rect == null || a <= 0f || rect.isEmpty) return@drawWithContent
            val w = RingWidth.toPx()
            val grow = HostRingGap.toPx() + w / 2f
            val radius = minOf(rect.minDimension / 2f, cornerCap.toPx())
            drawRoundRect(
                TvGlass.Peach.copy(alpha = a),
                topLeft = Offset(rect.left - grow, rect.top - grow),
                size = Size(rect.width + grow * 2, rect.height + grow * 2),
                cornerRadius = CornerRadius(radius + grow),
                style = Stroke(w),
            )
        }
}

/** Where the ring is: the host's own coordinates, the focused piece's, and the last place it was drawn (kept while it fades). */
@Stable
private class RingTarget {
    var host: LayoutCoordinates? = null
    var focused: LayoutCoordinates? = null
    var last: Rect? = null
    var shown by mutableStateOf(false)
    /** Bumped on every report of the focused piece's bounds (focus moving, or the piece moving with a scroll), to redraw. */
    var moves by mutableIntStateOf(0)
}

/**
 * The ring's gap here: closer than the TV's own pieces, as Watch's guide and lists set their pieces edge to edge (a channel's cell
 * fills its row), so a ring further out would run into the rows beside it.
 */
private val HostRingGap = 2.dp

/** Marks a [tvRingHost] for [isUnderRingHost]. */
private object RingHostKey

private class RingHostNode : Modifier.Node(), TraversableNode {
    override val traverseKey: Any get() = RingHostKey
}

private object RingHostElement : ModifierNodeElement<RingHostNode>() {
    override fun create(): RingHostNode = RingHostNode()
    override fun update(node: RingHostNode) = Unit
    override fun hashCode(): Int = 31
    override fun equals(other: Any?): Boolean = other === this
}

/** Whether a [tvRingHost] above this node already rings it on focus. */
internal fun DelegatableNode.isUnderRingHost(): Boolean = findNearestAncestor(RingHostKey) != null
