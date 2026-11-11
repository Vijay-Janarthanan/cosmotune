package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.cosmotune.lyrics.styles.LyricCursor
import com.cosmotune.lyrics.styles.LyricStyle
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.lyrics.styles.StretchCurve
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.archivo_variable
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.theme.cosmoFontFamilies
import org.jetbrains.compose.resources.Font

/** What a stage knows of the song beyond its lines: the cover its disc and Poster's colour come from, and the names Poster prints. */
@Immutable
class LyricStageSong(val title: String, val artist: String, val album: String?, val coverUrl: String?)

/**
 * The faces the stage styles set their lines in: Archivo cut at fixed instances (never moved per frame; see [StretchCurve]),
 * each one a family of its own so a style asks for it by family, and Hanken Grotesk for the small lines. Made once; the video
 * export makes the same set in its own composition.
 */
@Immutable
class LyricStageFonts internal constructor(
    /** Orbital's line: `wdth` 100, `wght` 800. */
    val orbital: FontFamily,
    /** Pulse's line: `wdth` 118, `wght` 850. */
    val pulse: FontFamily,
    /** Poster's caps: `wdth` 62, `wght` 900. */
    val poster: FontFamily,
    /** Stretch's [StretchCurve.INSTANCES] instances, from narrow and light to wide and heavy. */
    val stretch: List<FontFamily>,
    /** Hanken Grotesk, for the next line and the labels. */
    val text: FontFamily,
)

internal object StageWeights {
    const val ORBITAL = 800
    const val PULSE = 850
    const val POSTER = 900
    fun stretch(index: Int): Int = StretchCurve.weight(StretchCurve.instanceAmount(index))
}

@Composable
fun rememberLyricStageFonts(): LyricStageFonts {
    val orbital = archivo(StageWeights.ORBITAL, 100f)
    val pulse = archivo(StageWeights.PULSE, 118f)
    val poster = archivo(StageWeights.POSTER, 62f)
    val stretch = List(StretchCurve.INSTANCES) { i -> archivo(StageWeights.stretch(i), StretchCurve.width(StretchCurve.instanceAmount(i))) }
    val text = cosmoFontFamilies().second
    return remember(orbital, pulse, poster, stretch, text) {
        LyricStageFonts(FontFamily(orbital), FontFamily(pulse), FontFamily(poster), stretch.map { FontFamily(it) }, text)
    }
}

@Composable
private fun archivo(weight: Int, width: Float) = Font(
    Res.font.archivo_variable, FontWeight(weight), FontStyle.Normal,
    FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** A text style in [family] at [sizePx] (in px of [density]), asking for exactly the instance's [weight] and never a synthetic one. */
internal fun stageStyle(family: FontFamily, weight: Int, sizePx: Float, density: Density, lineHeight: Float = 1.08f, italic: Boolean = false): TextStyle {
    val size: TextUnit = with(density) { sizePx.toSp() }
    return TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = size,
        lineHeight = size * lineHeight,
        fontSynthesis = FontSynthesis.None,
    )
}

/** The text style of a small stage line (the next line, a label) in Hanken Grotesk. */
internal fun stageTextStyle(fonts: LyricStageFonts, sizePx: Float, density: Density, weight: Int = 600): TextStyle =
    stageStyle(fonts.text, weight, sizePx, density, lineHeight = 1.25f).copy(fontSynthesis = FontSynthesis.All)

/**
 * The colours of a stage, from the palette on screen (the cover's, on Now Playing): the words, their unlit state, the next line,
 * the faint track the moon runs on, and the cover's own colour for the moon and the glow. Made once per cover.
 */
@Immutable
internal class StageInk(
    val text: Color,
    val unlit: Color,
    val next: Color,
    val faint: Color,
    val accent: Color,
    val ground: Color,
    val dark: Boolean,
)

internal fun stageInkOf(palette: CoverColors, dark: Boolean): StageInk {
    val text = Color(palette.textPrimary)
    return StageInk(
        text = text,
        unlit = text.copy(alpha = 0.28f),
        next = Color(palette.textSecondary),
        faint = text.copy(alpha = if (dark) 0.12f else 0.14f),
        accent = Color(palette.accentStrong),
        ground = Color(palette.backgroundTop),
        dark = dark,
    )
}

/**
 * Where a stage is in the song: the clock (read only in draw lambdas and derived states), and from it, changing once a line, the
 * line in focus (the one being sung, or the one a break waits on) and the line after it.
 */
@Stable
internal class StageCursor(val lines: List<LyricLine>, val clock: LongState) {
    val focus = derivedStateOf { LyricCursor.focus(lines, clock.longValue) }
    val next = derivedStateOf { focus.value.let { if (it < 0) -1 else LyricCursor.nextSung(lines, it) } }
}

/** A stage's line, laid out once when it comes into focus. */
internal fun stageLineOf(lines: List<LyricLine>, index: Int): StageLine? = if (index in lines.indices) StageLine.of(lines, index) else null

/**
 * Where each word of a [StageLine] sits in [layout], as boxes, so a frame can light a word by clipping to it. Built once with the
 * layout; a word that breaks across two rows is lit on the row it starts on.
 */
internal class WordBoxes(layout: TextLayoutResult, stage: StageLine) {
    val count = stage.count
    val left = FloatArray(count)
    val top = FloatArray(count)
    val right = FloatArray(count)
    val bottom = FloatArray(count)

    init {
        val length = layout.layoutInput.text.length
        for (i in 0 until count) {
            val first = stage.charStart[i].coerceIn(0, (length - 1).coerceAtLeast(0))
            val last = (stage.charEnd[i] - 1).coerceIn(first, (length - 1).coerceAtLeast(0))
            if (length == 0) continue
            val a = layout.getBoundingBox(first)
            val b = layout.getBoundingBox(last)
            val row = layout.getLineForOffset(first)
            top[i] = layout.getLineTop(row)
            bottom[i] = layout.getLineBottom(row)
            if (layout.getLineForOffset(last) == row) {
                left[i] = minOf(a.left, b.left)
                right[i] = maxOf(a.right, b.right)
            } else {
                left[i] = minOf(a.left, layout.getLineLeft(row))
                right[i] = maxOf(a.right, layout.getLineRight(row))
            }
        }
    }
}

/** How long a word takes to light, in the styles that light a word at a time (the board's `transition: color .15s`). */
internal const val WORD_LIGHT_MS = 150f

/**
 * Draws [layout] at [topLeft] in [unlit] and, over it, each word of [stage] that has started by [positionMs] in [lit], fading in
 * over [WORD_LIGHT_MS] (with [reduced] motion, at once). Index loops and value types only: nothing is allocated.
 */
internal fun DrawScope.drawWordsLit(
    layout: TextLayoutResult,
    boxes: WordBoxes,
    stage: StageLine,
    positionMs: Long,
    unlit: Color,
    lit: Color,
    topLeft: Offset,
    reduced: Boolean,
    pad: Float,
) {
    drawText(layout, unlit, topLeft)
    for (i in 0 until boxes.count) {
        val since = positionMs - stage.startMs[i]
        if (since < 0L) break
        val alpha = if (reduced) 1f else (since / WORD_LIGHT_MS).coerceIn(0f, 1f)
        clipRect(topLeft.x + boxes.left[i] - pad, topLeft.y + boxes.top[i], topLeft.x + boxes.right[i] + pad, topLeft.y + boxes.bottom[i]) {
            drawText(layout, lit.copy(alpha = lit.alpha * alpha), topLeft)
        }
    }
}

/**
 * What a touch on a stage does: a tap seeks to the line [target] picks for the place touched (null leaves it), a long press
 * starts a pick of lines for a card at the line in focus where the platform makes cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.stageTouch(target: (Offset) -> Long?, onSeek: (Long) -> Unit, onLongPress: (() -> Unit)?): Modifier {
    val pick by rememberUpdatedState(target)
    val seek by rememberUpdatedState(onSeek)
    val long by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        detectTapGestures(
            onTap = { at -> pick(at)?.let(seek) },
            onLongPress = { long?.invoke() },
        )
    }
}

/**
 * The stage styles, one composable: [style] decides which. Each follows [clock] (the frame clock the list uses), lays out once a
 * line, and draws a frame without allocating. Orbit Read is not here: it is the list ([CosmoLyricsView]).
 */
@Composable
internal fun CosmoLyricStage(
    style: LyricStyle,
    lines: List<LyricLine>,
    subLines: List<LyricLine>?,
    clock: LongState,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    song: LyricStageSong?,
    modifier: Modifier = Modifier,
    onPick: ((Int) -> Unit)? = null,
    fullScreen: Boolean = false,
) {
    val cursor = remember(lines, clock) { StageCursor(lines, clock) }
    val longPress = onPick?.let { pick -> { cursor.focus.value.takeIf { it >= 0 }?.let(pick); Unit } }
    Box(modifier) {
        when (style) {
            LyricStyle.ORBITAL -> OrbitalStage(cursor, song, onSeek, longPress, fullScreen, Modifier.matchParentSize())
            LyricStyle.STRETCH -> StretchStage(cursor, onSeek, longPress, Modifier.matchParentSize())
            LyricStyle.CALL_AND_ANSWER -> CallAndAnswerStage(lines, subLines, cursor, onSeek, longPress, Modifier.matchParentSize())
            LyricStyle.PULSE -> PulseStage(cursor, isPlaying, onSeek, longPress, Modifier.matchParentSize())
            LyricStyle.POSTER -> PosterStage(cursor, song, onSeek, longPress, Modifier.matchParentSize(), corner = if (fullScreen) 0f else 30f)
            LyricStyle.ORBIT_READ -> Unit
        }
    }
}

/**
 * A stage style for a screen outside Now Playing's panel (the TV): the same stages, on their own frame clock from [position].
 * [style] must not be [LyricStyle.ORBIT_READ], which is the list. [corner] rounds Poster's card (0 for a full screen).
 */
@Composable
fun LyricStyleStage(
    style: LyricStyle,
    lines: List<LyricLine>,
    trackKey: String,
    position: com.cosmotune.music.playback.PlaybackPosition,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    song: LyricStageSong?,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
) {
    androidx.compose.runtime.key(trackKey) {
        val playhead = com.cosmotune.music.ui.player.rememberLyricPlayhead(position)
        val clock = rememberCosmoLyricClock(trackKey, playhead, isPlaying)
        CosmoLyricStage(style, lines, null, clock, isPlaying, onSeek, song, modifier, fullScreen = fullScreen)
    }
}
