package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.lyrics.LyricBacking
import com.cosmotune.lyrics.SubReveal
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.music.data.lyrics.LyricLine
import kotlin.math.roundToInt

/*
 * The smaller voices of a lyrics row in the new Now Playing (the old view's feature, carried over): the second voice that answers or echoes a line (the old
 * panel's `line.background`), the romanisation or translation that opens under a line, and the section markers of a plain-text song.
 */

/**
 * What is drawn under each line, and how far it has opened. One value for the whole list rather than an animation per row: a long
 * song is a hundred rows, and each would otherwise start, run and stop its own animation on every toggle. [progress] is read only
 * in layout and layer blocks (see [revealBelow]), so opening it relayouts the rows on screen and recomposes none of them.
 */
@Stable
internal class SubRevealState(val progress: State<Float>, lines: State<List<LyricLine>?>) {
    /** The words, held through the closing so that they fold away rather than vanish. */
    val lines: List<LyricLine>? by lines
}

/**
 * The reveal of [target] (the romanisation or translation, index for index with the lines; null for none), by the old panel's
 * rules ([SubReveal.plan]): reduced motion has it there or not; asked for it opens; switched from one script to the other, the
 * first folds away before the second opens, so two scripts never share the gap; put away it folds away and is forgotten.
 * Keyed to the track: a new song arrives with nothing under it and must not fold the last song's translation away over its
 * own opening lines.
 */
@Composable
internal fun rememberSubReveal(target: List<LyricLine>?, trackKey: String, reduced: Boolean): SubRevealState {
    val shown = remember(trackKey) { mutableStateOf(target) }
    val progress = remember(trackKey) { Animatable(if (target != null) 1f else 0f) }
    LaunchedEffect(target, trackKey, reduced) {
        when (SubReveal.plan(reduced, target != null, shown.value != null, shown.value === target, progress.value)) {
            SubReveal.Plan.SNAP_OPEN -> {
                shown.value = target
                progress.snapTo(1f)
            }
            SubReveal.Plan.SNAP_CLOSED -> {
                shown.value = null
                progress.snapTo(0f)
            }
            SubReveal.Plan.OPEN -> {
                shown.value = target
                progress.animateTo(1f, CosmoMotion.move(false))
            }
            SubReveal.Plan.CLOSE_THEN_OPEN -> {
                progress.animateTo(0f, CosmoMotion.move(false))
                shown.value = target
                progress.animateTo(1f, CosmoMotion.move(false))
            }
            SubReveal.Plan.CLOSE_THEN_CLEAR -> {
                progress.animateTo(0f, CosmoMotion.move(false))
                shown.value = null
            }
        }
    }
    return remember(trackKey) { SubRevealState(progress.asState(), shown) }
}

/**
 * Opens downward out of the line above: the height grows from nothing while the words slide down from behind the original and
 * fade up. Reads [progress] in layout and in the layer only ([LyricsMetrics.subRevealHeightPx] and its neighbours), never in composition.
 */
internal fun Modifier.revealBelow(progress: State<Float>): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val open = progress.value
    layout(placeable.width, LyricsMetrics.subRevealHeightPx(placeable.height, open)) {
        placeable.placeWithLayer(0, 0) {
            translationY = LyricsMetrics.subRevealShiftPx(placeable.height, open)
            alpha = LyricsMetrics.subRevealAlpha(open)
        }
    }
}

/**
 * Opens the answering voice under its lead as [progress] runs from 0 to 1: the room it takes opens with it, so the rows below make
 * way rather than jump; the words fade in a little ahead of the room being made ([LyricsMetrics.backingFade]), to its own strength
 * ([LyricsMetrics.BACKING_ALPHA]), and come the last few dp into place from a hair smaller. Scaled from the edge the line is
 * written from ([originX]: 0 the left edge, 1 the right), so a duet's second voice grows out of its own margin. Nothing is drawn
 * once it is closed: the layer is at zero alpha.
 */
internal fun Modifier.revealBacking(progress: State<Float>, originX: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val open = progress.value.coerceIn(0f, 1f)
    layout(placeable.width, (placeable.height * open).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            alpha = LyricsMetrics.backingFade(open) * LyricsMetrics.BACKING_ALPHA
            translationY = LyricsMetrics.backingRiseDp(open).dp.toPx()
            val grow = LyricsMetrics.backingScale(open)
            scaleX = grow
            scaleY = grow
            transformOrigin = TransformOrigin(originX, 0f)
        }
    }
}

/**
 * The second voice of a row, under its lead: smaller (two steps down) and dimmer, with its own words and its own clock. Only on
 * screen while it is being sung ([LyricBacking.wanted]; [raw] is the line as the provider gave it, [shown] as it is drawn): it opens just ahead of its first word and folds away once its last one is
 * done, not with the lead, which it routinely enters halfway through and outlasts. Left open on every row, a verse of echoes would
 * read as two lyrics interleaved. A voice with text but no timings of its own borrows the lead's. Its fill is the accent, drawn
 * on its own words and feathered like the lead's, and it does not lift or bloom: the movement is the lead's.
 */
@Composable
internal fun BackingVoice(
    raw: LyricLine,
    shown: LyricLine,
    env: LyricEnv,
    leadActive: State<Boolean>,
    /** Written from the right edge: its lead line's side (BC-33), which the backing voice keeps. */
    fromRight: Boolean,
    sub: String?,
) {
    val timed = shown.isWordSynced
    val align = if (fromRight) TextAlign.Right else TextAlign.Left
    val textStyle = remember(env.type, env.style.baseStep, align, shown.text) {
        env.type.forText(env.type.title(LyricsMetrics.backingStep(env.style.baseStep)).copy(textAlign = align), shown.text)
    }
    val subStyle = remember(env.subStyle, align) {
        env.subStyle.copy(fontSize = LyricsMetrics.SUB_BACKING_SIZE_SP.sp, lineHeight = (LyricsMetrics.SUB_BACKING_SIZE_SP * 1.35f).sp, textAlign = align)
    }
    val picking = env.selection.picking
    // Reads the clock only for a voice that has timings of its own: one that borrows the lead's never wakes a frame.
    val wanted = remember(raw, env.synced, picking, env.clock) {
        derivedStateOf {
            LyricBacking.wanted(raw, env.synced, picking, leadActive.value, if (raw.isWordSynced) env.clock.longValue else 0L)
        }
    }
    val open = animateFloatAsState(if (wanted.value) 1f else 0f, CosmoMotion.slow(env.reduced), label = "backingOpen")
    // The accent copy exists only while the voice is on show: a shut one asks the clock for nothing.
    val fillOn by remember { derivedStateOf { timed && open.value > 0f } }
    val layoutRef = remember(shown) { LayoutHolder() }
    val baseInk = remember(env.ink) { ColorProducer { env.ink.secondary } }
    val accentInk = remember(env.ink) { ColorProducer { env.ink.accent } }
    val subInk = remember(env.ink) { ColorProducer { env.ink.secondary } }

    Column(Modifier.fillMaxWidth().revealBacking(open, if (fromRight) 1f else 0f)) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            BasicText(
                text = shown.text,
                style = textStyle,
                color = baseInk,
                onTextLayout = { layoutRef.value = it },
                modifier = Modifier.fillMaxWidth(),
            )
            if (fillOn) {
                BasicText(
                    text = shown.text,
                    style = textStyle,
                    color = accentInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { }
                        // The feather erases into this layer, so it has to be one.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val feather = featherEdge()
                            onDrawWithContent { sweepWords(layoutRef.value, shown, env.clock.longValue, feather) }
                        },
                )
            }
        }
        if (sub != null) {
            BasicText(
                text = sub,
                style = subStyle,
                color = subInk,
                modifier = Modifier.fillMaxWidth().revealBelow(env.subReveal.progress).padding(top = 2.dp),
            )
        }
    }
}

/** The marker of a section ("[Chorus]") in a plain-text song, as a small chip above its lines. */
@Composable
internal fun LyricSectionHeader(marker: String, first: Boolean, env: LyricEnv) {
    val title = remember(marker) { marker.trim().removePrefix("[").removeSuffix("]").trim().uppercase() }
    val textStyle = remember(env.type) {
        env.type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
    }
    val ink = remember(env.ink) { ColorProducer { env.ink.primary } }
    Box(Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 24.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(CosmoRadii.xs))
                .background(env.ink.primary.copy(alpha = 0.12f))
                .padding(horizontal = 11.dp, vertical = 4.dp),
        ) {
            BasicText(title, style = textStyle, color = ink)
        }
    }
}

/** The text layout of the copy that is laid out always, kept for the draw phase (no state: it is written before the draw that reads it). */
internal class LayoutHolder { var value: TextLayoutResult? = null }
