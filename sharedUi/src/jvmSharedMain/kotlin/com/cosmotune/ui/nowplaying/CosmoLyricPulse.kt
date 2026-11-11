package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.lyrics.styles.PulseMotion
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.music.data.audio.AudioLevelReading
import com.cosmotune.music.data.audio.AudioLevels
import com.cosmotune.music.data.audio.AudioLevelsHub
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion

/** Pulse's line laid out: the line centred and word-boxed, the next line under it. Built once a line. */
internal class PulseScene(
    val stage: StageLine,
    val layout: TextLayoutResult,
    val boxes: WordBoxes,
    val top: Float,
    val next: TextLayoutResult?,
    val nextTop: Float,
    val moonY: Float,
    val moonFrom: Float,
    val moonTo: Float,
) {
    companion object {
        fun build(measurer: TextMeasurer, stage: StageLine, nextText: String?, fonts: LyricStageFonts, density: Density, width: Float, height: Float): PulseScene {
            val side = with(density) { 22.dp.toPx() }
            val maxWidth = (width - side * 2).coerceAtLeast(1f).toInt()
            // Board: 34 px on a 300-wide stage; smaller until the line fits four rows and leaves room for what is under it.
            var px = minOf(width / 8.8f, height / 6f).coerceAtLeast(12f)
            fun measureAt(size: Float) = measurer.measure(
                stage.text, stageStyle(fonts.pulse, StageWeights.PULSE, size, density, 1.05f).copy(textAlign = TextAlign.Center),
                constraints = Constraints(maxWidth = maxWidth),
            )
            var layout = measureAt(px)
            while (!(layout.lineCount <= 4 && layout.size.height <= height * 0.5f) && px > 14f) {
                px *= 0.9f
                layout = measureAt(px)
            }
            val next = nextText?.let {
                measurer.measure(it, stageTextStyle(fonts, (px * 0.5f).coerceAtLeast(12f), density).copy(textAlign = TextAlign.Center), constraints = Constraints(maxWidth = maxWidth), maxLines = 2)
            }
            val gap = px * 0.8f
            val total = layout.size.height + gap + (next?.size?.height ?: 0)
            val top = ((height - total) / 2f).coerceAtLeast(0f)
            var left = Float.MAX_VALUE
            var right = 0f
            for (row in 0 until layout.lineCount) {
                left = minOf(left, layout.getLineLeft(row))
                right = maxOf(right, layout.getLineRight(row))
            }
            val offset = (width - layout.size.width) / 2f
            return PulseScene(
                stage, layout, WordBoxes(layout, stage), top, next, top + layout.size.height + gap,
                moonY = top + layout.size.height + gap * 0.4f, moonFrom = offset + left, moonTo = offset + right,
            )
        }
    }
}

/** The audio the stage moves to, read once a frame into [reading]; [live] says whether the tap answered this frame. */
internal class PulseLevels(val source: AudioLevels?) {
    val reading = AudioLevelReading()
}

/**
 * Draws Pulse at [positionMs]: the cover-colour glow (swelling with the bass, or breathing slowly without levels), the line lit a
 * word at a time and nudged by a kick, the moon under it sparkling with the highs, the next line, and the bars along the foot.
 * With [reduced] motion the glow, the line and the bars stand still and only the words change colour. [glow] is the radial brush,
 * made once per size.
 */
internal fun DrawScope.drawPulse(
    scene: PulseScene,
    positionMs: Long,
    levels: PulseLevels?,
    playing: Boolean,
    ink: StageInk,
    glow: Brush,
    reduced: Boolean,
) {
    val live = !reduced && levels != null && levels.source?.read(System.nanoTime(), levels.reading) == true
    val bass: Float
    val mid: Float
    val high: Float
    val kick: Float
    if (live && levels != null) {
        val reading = levels.reading
        bass = reading.bass; mid = reading.mid; high = reading.high
        kick = if (reading.beatAtNanos == 0L) 0f else PulseMotion.kick((System.nanoTime() - reading.beatAtNanos) / 1_000_000f)
    } else {
        // No tap (or nothing playing through it): a slow breath of the song's own clock, on no tempo; still when paused or reduced.
        val breath = if (reduced || !playing) 0.35f else PulseMotion.breath(positionMs)
        bass = 0.25f + 0.4f * breath; mid = 0.2f + 0.2f * breath; high = 0.1f; kick = 0f
    }
    val cx = size.width / 2f
    val cy = scene.top + scene.layout.size.height / 2f
    val center = Offset(cx, cy)
    val glowScale = if (reduced) 1f else PulseMotion.glowScale(bass, kick)
    scale(glowScale, center) { drawCircle(glow, pulseGlowRadius(size), center, alpha = if (reduced) 0.4f else PulseMotion.glowAlpha(bass)) }

    val lineScale = if (reduced) 1f else PulseMotion.lineScale(kick)
    val topLeft = Offset((size.width - scene.layout.size.width) / 2f, scene.top)
    scale(lineScale, center) {
        drawWordsLit(scene.layout, scene.boxes, scene.stage, positionMs, ink.unlit, ink.text, topLeft, reduced, pad = 2f)
    }
    if (!reduced) {
        val fraction = scene.stage.progress(positionMs)
        val x = scene.moonFrom + (scene.moonTo - scene.moonFrom) * fraction
        val moon = Offset(x, scene.moonY)
        val r = 4.dp.toPx()
        drawLine(ink.faint, Offset(scene.moonFrom, scene.moonY), Offset(scene.moonTo, scene.moonY), strokeWidth = 2.dp.toPx())
        drawCircle(ink.accent.copy(alpha = 0.22f), r * 2.4f * PulseMotion.sparkle(high), moon)
        drawCircle(ink.accent, r, moon)
    }
    scene.next?.let { drawText(it, ink.next, Offset((size.width - it.size.width) / 2f, scene.nextTop)) }

    // The bars along the foot: bass at the left, highs at the right.
    val bars = PULSE_BARS
    val barWidth = 5.dp.toPx()
    val barGap = 4.dp.toPx()
    val maxHeight = 28.dp.toPx()
    val rowWidth = bars * barWidth + (bars - 1) * barGap
    val left = (size.width - rowWidth) / 2f
    val bottom = size.height - 18.dp.toPx()
    for (i in 0 until bars) {
        val h = maxHeight * (if (reduced) 0.3f else PulseMotion.bar(i, bars, bass, mid, high, positionMs))
        drawRoundRect(
            ink.accent.copy(alpha = 0.7f),
            topLeft = Offset(left + i * (barWidth + barGap), bottom - h),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

private const val PULSE_BARS = 14

/** The glow's brush, centred on the line: the cover's colour fading out by two thirds of the way (the board's radial gradient). */
internal fun pulseGlow(scene: PulseScene?, size: Size, accent: Color): Brush {
    val cy = scene?.let { it.top + it.layout.size.height / 2f } ?: (size.height / 2f)
    return Brush.radialGradient(
        0f to accent, 0.65f to accent.copy(alpha = 0f), 1f to Color.Transparent,
        center = Offset(size.width / 2f, cy), radius = pulseGlowRadius(size),
    )
}

internal fun pulseGlowRadius(size: Size): Float = size.minDimension * 0.42f

/** Pulse (board 05): the lyrics breathe with the music, from the audio level tap where the platform has one. */
@Composable
internal fun PulseStage(cursor: StageCursor, isPlaying: Boolean, onSeek: (Long) -> Unit, onLongPress: (() -> Unit)?, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = LocalCosmoColors.current
    val ink = remember(colors.palette, colors.dark) { stageInkOf(colors.palette, colors.dark) }
    val fonts = rememberLyricStageFonts()
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val lines = cursor.lines
    val focus = cursor.focus.value
    val next = cursor.next.value
    // The tap does no work until a stage listens, and stops when it goes.
    val levels = remember { PulseLevels(AudioLevelsHub.source) }
    DisposableEffect(levels) {
        val handle = levels.source?.listen()
        onDispose { handle?.close() }
    }
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val scene = remember(lines, focus, next, fonts, density, width, height) {
            stageLineOf(lines, focus)?.let { PulseScene.build(measurer, it, lines.getOrNull(next)?.text, fonts, density, width, height) }
        }
        val clock = cursor.clock
        Spacer(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = scene?.stage?.text.orEmpty() }
                .stageTouch(
                    target = { at -> if (scene != null && at.y >= scene.nextTop) lines.getOrNull(next)?.timeMs else scene?.stage?.line?.timeMs },
                    onSeek = onSeek, onLongPress = onLongPress,
                )
                .drawWithCache {
                    val glow = pulseGlow(scene, size, ink.accent)
                    onDrawBehind { if (scene != null) drawPulse(scene, clock.longValue, levels, isPlaying, ink, glow, reduced) }
                },
        )
    }
}
