package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.cosmotune.lyrics.LyricTextDirection
import com.cosmotune.lyrics.styles.StageFit
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.lyrics.styles.StretchCurve
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion

/**
 * Stretch's line, laid out: every word at every one of the [StretchCurve.INSTANCES] instances (so a frame only picks one), the
 * rows it breaks into (each with room for any one of its words to grow to its peak, so a row never breaks again while it is
 * sung), and the next line in small type under it. Built once a line; a frame lays nothing out.
 */
internal class StretchScene(
    val stage: StageLine,
    /** `layouts[word * INSTANCES + instance]`. */
    val layouts: Array<TextLayoutResult>,
    val rowStart: IntArray,
    val rows: Int,
    val rowHeight: Float,
    val baseline: Float,
    val gap: Float,
    val top: Float,
    val rtl: Boolean,
    val next: TextLayoutResult?,
    val nextTop: Float,
) {
    fun layout(word: Int, instance: Int): TextLayoutResult = layouts[word * StretchCurve.INSTANCES + instance]

    companion object {
        /** The type is as large as fits: the line's rows and the next line within [height], the widest word at its peak within [width]. */
        fun build(
            measurer: TextMeasurer,
            stage: StageLine,
            nextText: String?,
            fonts: LyricStageFonts,
            density: Density,
            width: Float,
            height: Float,
            /** How many of the type's size fit the width: the board's 7.9; a story frame, seen whole, takes it larger. */
            perWidth: Float = 7.9f,
        ): StretchScene {
            val n = stage.count
            val words = Array(n) { stage.text.substring(stage.charStart[it], stage.charEnd[it]) }
            val ref = 100f
            val doneAt = StretchCurve.instance(StretchCurve.DONE)
            val peakAt = StretchCurve.INSTANCES - 1
            fun widthAt(word: Int, instance: Int, px: Float): Float =
                measurer.measure(words[word], stageStyle(fonts.stretch[instance], StageWeights.stretch(instance), px, density, 1f), softWrap = false, maxLines = 1).size.width.toFloat()
            val settledRef = FloatArray(n) { widthAt(it, doneAt, ref) }
            val peakRef = FloatArray(n) { widthAt(it, peakAt, ref) }
            val widestPeak = peakRef.maxOrNull() ?: ref
            // Board: 38 px words on a 300-wide stage. Never so large the widest word at its peak overruns the width.
            var px = minOf(width / perWidth, height / 3.2f, width / (widestPeak / ref) * 0.98f).coerceAtLeast(10f)
            val rowStart = IntArray(n.coerceAtLeast(1))
            fun rowsAt(size: Float): Int {
                val k = size / ref
                return StageFit.stretchRows(FloatArray(n) { settledRef[it] * k }, FloatArray(n) { peakRef[it] * k }, n, size * GAP, width, rowStart)
            }
            // The rows, the next line's half-size type and the gap above it all within the height.
            var rows = rowsAt(px)
            while (rows * px * ROW + px * 0.55f * 1.25f + px * 0.6f > height && px > 12f) {
                px *= 0.92f
                rows = rowsAt(px)
            }
            val nextPx = px * 0.5f
            val layouts = Array(n * StretchCurve.INSTANCES) { k ->
                val word = k / StretchCurve.INSTANCES
                val instance = k % StretchCurve.INSTANCES
                measurer.measure(words[word], stageStyle(fonts.stretch[instance], StageWeights.stretch(instance), px, density, ROW), softWrap = false, maxLines = 1)
            }
            val next = nextText?.let {
                measurer.measure(it, stageTextStyle(fonts, nextPx.coerceAtLeast(12f), density).copy(textAlign = TextAlign.Center), constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)), maxLines = 2)
            }
            val blockHeight = rows * px * ROW
            val nextHeight = next?.let { it.size.height + px * 0.6f } ?: 0f
            val top = ((height - blockHeight - nextHeight) / 2f).coerceAtLeast(0f)
            val baseline = layouts.firstOrNull()?.firstBaseline ?: px
            return StretchScene(stage, layouts, rowStart, rows, px * ROW, baseline, px * GAP, top, LyricTextDirection.isRtl(stage.text), next, top + blockHeight + px * 0.6f)
        }

        /** The space between two words, and a row's height, as fractions of the size (the board's `gap: 2px 10px` at 38 px, a little more air). */
        const val GAP = 0.26f
        const val ROW = 1.04f
    }
}

/**
 * Draws [scene] at [positionMs]: each row centred at the width its words have now, each word at the instance its place on
 * [StretchCurve]'s path asks for, unlit before it is sung, bright while it is ([live]) and in the cover's colour once it has been.
 * With [reduced] motion every word keeps one width and only its colour changes.
 */
internal fun DrawScope.drawStretch(scene: StretchScene, positionMs: Long, unlit: Color, live: Color, done: Color, next: Color, reduced: Boolean) {
    val stage = scene.stage
    val width = size.width
    for (r in 0 until scene.rows) {
        val first = scene.rowStart[r]
        val end = if (r + 1 < scene.rows) scene.rowStart[r + 1] else stage.count
        var total = 0f
        for (w in first until end) {
            val x = StretchCurve.amount(stage.startMs[w], stage.wordEndMs[w], stage.wordSung(w, positionMs), positionMs, reduced)
            total += scene.layout(w, StretchCurve.instance(x)).size.width
            if (w > first) total += scene.gap
        }
        val baselineY = scene.top + r * scene.rowHeight + scene.baseline
        var x = if (scene.rtl) (width + total) / 2f else (width - total) / 2f
        for (w in first until end) {
            val amount = StretchCurve.amount(stage.startMs[w], stage.wordEndMs[w], stage.wordSung(w, positionMs), positionMs, reduced)
            val layout = scene.layout(w, StretchCurve.instance(amount))
            val wordWidth = layout.size.width
            val color = when {
                positionMs < stage.startMs[w] -> unlit
                positionMs < stage.wordEndMs[w] -> live
                else -> done
            }
            val left = if (scene.rtl) x - wordWidth else x
            drawText(layout, color, Offset(left, baselineY - layout.firstBaseline))
            x = if (scene.rtl) x - wordWidth - scene.gap else x + wordWidth + scene.gap
        }
    }
    scene.next?.let { drawText(it, next, Offset((width - it.size.width) / 2f, scene.nextTop)) }
}

/** Stretch (board 03): the word being sung widens and gets heavier for as long as it is held, then settles back. */
@Composable
internal fun StretchStage(cursor: StageCursor, onSeek: (Long) -> Unit, onLongPress: (() -> Unit)?, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = LocalCosmoColors.current
    val ink = remember(colors.palette, colors.dark) { stageInkOf(colors.palette, colors.dark) }
    val fonts = rememberLyricStageFonts()
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val lines = cursor.lines
    val focus = cursor.focus.value
    val next = cursor.next.value
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val scene = remember(lines, focus, next, fonts, density, width, height) {
            stageLineOf(lines, focus)?.let { StretchScene.build(measurer, it, lines.getOrNull(next)?.text, fonts, density, width, height) }
        }
        val clock = cursor.clock
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = scene?.stage?.text.orEmpty() }
                .stageTouch(
                    target = { at -> if (scene != null && at.y >= scene.nextTop) lines.getOrNull(next)?.timeMs else scene?.stage?.line?.timeMs },
                    onSeek = onSeek, onLongPress = onLongPress,
                ),
        ) {
            if (scene != null) drawStretch(scene, clock.longValue, ink.unlit, ink.text, ink.accent, ink.next, reduced)
        }
    }
}
