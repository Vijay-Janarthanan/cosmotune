package com.cosmotune.video.ui.blocks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.delay

/*
 * The mouse-over state of a card (the desktop; a touch screen never hovers): whether the pointer is on it, and how far its picture
 * has lifted. Cards and rows share it so every picture in the mode answers a mouse the same way.
 */

/** How much a hovered picture grows: 2 %, enough to feel lifted without moving the text under it. */
private const val HOVER_LIFT = 1.02f

/** The lift's time in ms. */
private const val LIFT_MS = 160

/** The pointer's state on one card. */
@Stable
internal class CardHover(private val source: MutableInteractionSource, hovered: State<Boolean>, private val lift: State<Float>) {
    private val hoveredState = hovered

    /** Whether the pointer is on the card now. */
    val hovered: Boolean get() = hoveredState.value

    /** Tracks the pointer on the card: put it on the block that is the button. */
    fun Modifier.track(): Modifier = hoverable(source)

    /** The picture's lift, read in the layer block so a frame of it redraws and recomposes nothing. */
    fun Modifier.lifted(): Modifier = graphicsLayer {
        val scale = lift.value
        scaleX = scale
        scaleY = scale
    }
}

/** A [CardHover] for one card. */
@Composable
internal fun rememberCardHover(): CardHover {
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState()
    val lift = animateFloatAsState(if (hovered.value) HOVER_LIFT else 1f, tween(LIFT_MS), label = "cardLift")
    return remember(source, hovered, lift) { CardHover(source, hovered, lift) }
}

/**
 * What a card under the pointer asks for ahead of its click: the shell's player resolves the video's streams (a short-lived cache), so a
 * click a moment later starts playing at once. Nothing where no shell provides it (a render test, a card outside Video).
 */
val LocalVideoPreResolve = staticCompositionLocalOf<(VideoItem) -> Unit> { {} }

/** How long the pointer rests on a card before its streams are resolved: a pointer passing over does not count. */
internal const val PRE_RESOLVE_DWELL_MS = 300L

/** Resolves [item]'s streams ahead of the click once the pointer has rested on its card for [PRE_RESOLVE_DWELL_MS]. */
@Composable
internal fun PreResolveOnHover(hover: CardHover, item: VideoItem) {
    val preResolve = LocalVideoPreResolve.current
    val hovered = hover.hovered
    LaunchedEffect(hovered, item.id) {
        if (!hovered || item.isShort || item.isUpcoming || item.isLive) return@LaunchedEffect
        delay(PRE_RESOLVE_DWELL_MS)
        preResolve(item)
    }
}
