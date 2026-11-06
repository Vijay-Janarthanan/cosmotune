package com.cosmotune.ui.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import com.cosmotune.ui.components.cosmoFocusRing
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons

/**
 * The play / pause button: peach with a dark glyph in dark, near-black with a peach glyph in light. Lives in a bite. A [touch]
 * larger than [size] (the dock's 48 dp) is the button's touch target round the drawn circle, overhanging what the row lays out
 * ([orbitTouchOverhang]), so the look stays as drawn.
 */
@Composable
fun OrbitPlayButton(playing: Boolean, onClick: () -> Unit, look: OrbitLook, modifier: Modifier = Modifier, size: Dp = 66.dp, touch: Dp = 0.dp) {
    val fill = if (look.dark) PEACH else Color(0xFF1D1416)
    val glyph: @Composable () -> Unit = {
        Icon(if (playing) CosmoIcons.Pause else CosmoIcons.Play, contentDescription = if (playing) "Pause" else "Play",
            tint = if (look.dark) Color(0xFF2A100A) else PEACH, modifier = Modifier.size(size * 0.38f))
    }
    if (touch > size) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            modifier.orbitTouchOverhang(size, touch).size(touch)
                .clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size).clip(CircleShape).background(fill).indication(interaction, androidx.compose.foundation.LocalIndication.current),
                contentAlignment = Alignment.Center,
            ) { glyph() }
        }
        return
    }
    Box(
        modifier.size(size).clip(CircleShape).background(fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/**
 * Lays out a [target]-square box (a button's touch target) as [visible]-square, centred on it: the row round it keeps the drawn
 * size and spacing, while the box, its click and its semantics keep the full [target], overhanging by the difference.
 */
fun Modifier.orbitTouchOverhang(visible: Dp, target: Dp): Modifier = layout { measurable, _ ->
    val t = target.roundToPx()
    val v = visible.roundToPx()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(t, t))
    layout(v, v) { placeable.place((v - t) / 2, (v - t) / 2) }
}

/** The round tonal button of a card or a bar (previous, next, like, the screen button): [ink] at 12 %, or solid when [on]. */
@Composable
fun OrbitRoundButton(
    icon: ImageVector,
    label: String,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    on: Boolean = false,
    onInk: Color = Color(0xFF1B1416),
    enabled: Boolean = true,
    /** A touch target larger than [size] round the drawn circle (see [OrbitPlayButton]); 0 is the circle itself. */
    touch: Dp = 0.dp,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    if (touch > size) {
        Box(
            modifier.alpha(if (enabled) 1f else 0.45f).orbitTouchOverhang(size, touch).size(touch)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = label,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size).cosmoFocusRing(interaction, size / 2).clip(CircleShape)
                    .background(if (on) ink else ink.copy(alpha = 0.12f))
                    .indication(interaction, androidx.compose.foundation.LocalIndication.current),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = label, tint = if (on) onInk else ink, modifier = Modifier.size(size * 0.45f)) }
        }
        return
    }
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f).size(size).cosmoFocusRing(interaction, size / 2).clip(CircleShape)
            .background(if (on) ink else ink.copy(alpha = 0.12f))
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (on) onInk else ink, modifier = Modifier.size(size * 0.45f)) }
}

/**
 * The seek line with its moon: [progress] (0..1, read in draw) of the line in [ink], the rest faint, the moon at the current point.
 * Tapping or dragging calls [onSeek] with the fraction (on letting go; with [live] all the way through the drag, for a level such
 * as the volume); null makes it a read-only line.
 */
@Composable
fun OrbitProgressLine(progress: () -> Float, ink: Color, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null, moon: Dp = 6.dp, live: Boolean = false) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val input = if (onSeek == null) Modifier else Modifier
        .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(onSeek) {
            detectDragGestures(
                onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let(onSeek); drag = null },
                onDragCancel = { drag = null },
            ) { change, _ ->
                change.consume()
                drag = (change.position.x / size.width).coerceIn(0f, 1f)
                if (live) drag?.let(onSeek)
            }
        }
    Canvas(modifier.fillMaxWidth().height(moon * 2 + 8.dp).then(input)) {
        val p = (drag ?: progress()).coerceIn(0f, 1f)
        val y = size.height / 2
        val w = 4.dp.toPx()
        drawLine(ink.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
        if (p > 0f) drawLine(ink, Offset(0f, y), Offset(size.width * p, y), w, StrokeCap.Round)
        drawCircle(ink.copy(alpha = 0.18f), moon.toPx() + 4.dp.toPx(), Offset(size.width * p, y))
        drawCircle(ink, moon.toPx(), Offset(size.width * p, y))
    }
}

/** Play as a small moon on the bottom-end edge of a disc, ringed in the page's ground so the cover stays clear. Use inside the disc's box. */
@Composable
fun BoxScope.DiscPlayMoon(
    look: OrbitLook,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    label: String = "Play",
    /** How far past the corner it sits (a picture's moon hangs 10 dp off it; a disc's sits on its edge). */
    overhang: Dp = 0.dp,
    iconScale: Float = 0.38f,
) {
    Box(
        Modifier.align(Alignment.BottomEnd).offset(x = overhang, y = overhang).size(size).clip(CircleShape).background(look.ground)
            .padding(4.dp).clip(CircleShape).background(PEACH)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(CosmoIcons.Play, contentDescription = label, tint = Color(0xFF2A100A), modifier = Modifier.size(size * iconScale)) }
}

/**
 * Home's colour card: flat [fill], a round bite out of its bottom-end corner ([shape]) and [bite] standing in it (usually an
 * [OrbitPlayButton]). The bite button is outside the clipped card, so it is never cut. Given a size the card fills it; given
 * only a width it is as tall as its [content].
 */
@Composable
fun OrbitBiteCard(
    fill: Color,
    modifier: Modifier = Modifier,
    shape: BiteShape = HeroBite,
    biteInset: Dp = 13.dp,
    onClick: (() -> Unit)? = null,
    bite: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // The card takes the size it is given, or its content's height when it is given only a width.
    Box(modifier, propagateMinConstraints = true) {
        Box(
            Modifier.clip(shape).background(fill).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            content = content,
        )
        Box(Modifier.align(Alignment.BottomEnd).wrapContentSize(Alignment.BottomEnd).padding(biteInset), content = bite)
    }
}
