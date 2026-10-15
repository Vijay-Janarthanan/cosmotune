package com.cosmotune.design

import kotlin.math.roundToInt

/**
 * The lyrics view of the new Now Playing: its numbers (`screens-v3.css:157-166`, the `.lyr2` block) and the small decisions
 * made from them. Pure, so they are tested on the JVM; the Compose side only draws what these say. Sizes are dp unless the
 * name says otherwise.
 *
 * The board changes the font size from one line to the next (past lines at title-m, the line being sung at display-s). The
 * app does not: every line is laid out once at the style's base step, and a line's state is a scale applied by a graphics
 * layer from its start edge, so the list never lays out again when the song moves from one line to the next.
 */
object LyricsMetrics {
    /** The gap between two lines (`.lyr2 { gap: 14px }`). */
    const val LINE_GAP_DP = 14f

    /** The page margin the lyrics keep on each side (`.lyr2 { left: 20px; right: 20px }`). */
    const val PORTRAIT_SIDE_DP = 20f

    /**
     * Where the first line starts, measured from the bottom of the top bar. The board puts it 150 dp from the top of the
     * window, under a 48 dp status area and a 48 dp bar.
     */
    const val PORTRAIT_TOP_PADDING_DP = 150f - (48f + 48f)

    /** Lines already sung and lines still to come are dimmed (`.ln.past { opacity: .8 }`, `.ln.up { opacity: .78 }`). */
    const val PAST_ALPHA = 0.8f
    const val UPCOMING_ALPHA = 0.78f

    /** The line being sung is scaled from its start edge by this much in a portrait window, the lines already sung by [PORTRAIT_PAST_SCALE]. */
    const val PORTRAIT_CURRENT_SCALE = 1.2f
    const val PORTRAIT_PAST_SCALE = 0.86f

    /** The base step of a line (title-l, `--fs-title-l`); a landscape window has less room and takes title-m. */
    const val PORTRAIT_BASE_STEP = TypeScale.TITLE_L
    const val LANDSCAPE_BASE_STEP = TypeScale.TITLE_M

    /** Romanisation and translation under a line: 14 sp, weight 500 (`.lyr2 .rom`). */
    const val SUB_SIZE_SP = 14f
    const val SUB_WEIGHT = 500

    /** A finger on the list stops it following the song for this long after the last touch. */
    const val FOLLOW_PAUSE_MS = 2_500L

    /** The line being sung rests this far down the list: one third of its height. */
    private const val ANCHOR_DIVISOR = 3

    /** The empty room under the last line, so that it can rise to the anchor: two thirds of the list's height, and a little over. */
    const val BOTTOM_ROOM_OF_HEIGHT = 0.7f

    /** A closed gap (an instrumental) is three dots of this size, [GAP_DOT_GAP_DP] apart. */
    const val GAP_DOT_DP = 8f
    const val GAP_DOT_GAP_DP = 8f

    /** How far a line is from being sung. */
    enum class LineState { PAST, CURRENT, UPCOMING }

    /**
     * The state of line [index] when [current] is the index of the line being sung (-1 before the first line, and for a song
     * that has no timings). Before the first line, and without timings, nothing is past and nothing is current.
     */
    fun lineState(index: Int, current: Int): LineState = when {
        current < 0 -> LineState.UPCOMING
        index < current -> LineState.PAST
        index == current -> LineState.CURRENT
        else -> LineState.UPCOMING
    }

    /**
     * The scale a line of [state] is drawn at. With reduced motion nothing scales: a line changes state without growing or
     * shrinking, only its colour says where the song is.
     */
    fun scaleFor(state: LineState, currentScale: Float, pastScale: Float, reducedMotion: Boolean): Float = when {
        reducedMotion -> 1f
        state == LineState.CURRENT -> currentScale
        state == LineState.PAST -> pastScale
        else -> 1f
    }

    /** The alpha a line of [state] is drawn at (the line being sung is drawn at full strength; its fill is a second copy). */
    fun alphaFor(state: LineState): Float = when (state) {
        LineState.PAST -> PAST_ALPHA
        LineState.UPCOMING -> UPCOMING_ALPHA
        LineState.CURRENT -> 1f
    }

    /**
     * The width in px a line is laid out at: the list's width divided by the largest scale a line is drawn at, so a line that
     * fills the width at rest still fits inside the list when it is the one being sung. Never above the list's width: a scale
     * under 1 would otherwise let a line run out of it.
     */
    fun layoutWidthPx(listWidthPx: Int, currentScale: Float): Int {
        val scale = if (currentScale < 1f) 1f else currentScale
        return (listWidthPx / scale).roundToInt()
    }

    /** `animateScrollToItem`'s offset that leaves the item one third of the list's height from the top (a negative offset moves it down). */
    fun followOffsetPx(viewportHeightPx: Int): Int = -(viewportHeightPx / ANCHOR_DIVISOR)

    /** The empty room in px under the last line. */
    fun bottomRoomPx(viewportHeightPx: Int): Int = (viewportHeightPx * BOTTOM_ROOM_OF_HEIGHT).roundToInt()

    /**
     * How many characters of a line of [length] are sung at [fraction] (0 to 1, as [com.cosmotune.lyrics.SungFraction.of] gives
     * it): a fractional position, so that the edge of the fill moves smoothly through a character.
     */
    fun sungChars(fraction: Float, length: Int): Float = fraction.coerceIn(0f, 1f) * length
}
