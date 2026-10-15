package com.cosmotune.design

/**
 * The numbers of the content blocks (list rows, cards, shelves, mood tiles), each with the style rule it comes from
 * (`v3/components-v3bc.css`, `v2/screens-v2.css`, `v2/v2.js`). Dp as floats, like [ControlMetrics].
 */
object BlockMetrics {
    // ---- list rows ----
    /** `.mrow`: a list row is 64 dp tall. */
    const val ROW_DP = 64f

    /** `.tracks2 .mrow`: the rows of an album's track list are 60 dp. */
    const val TRACK_ROW_DP = 60f

    /** `.d2-tracks li`: the desktop's track rows are 54 dp. */
    const val DESKTOP_ROW_DP = 54f

    /** `.mrow .cv`: the cover in a row is 52 dp (radius 12). */
    const val ROW_COVER_DP = 52f

    // ---- cards ----
    /** `.lead2` (`.lead2 .cv`, `v2.js:64` `--r:22px`): the first card of a shelf. */
    const val LEAD_CARD_DP = 168f

    /** `.small2` (`v2.js:64` `--r:16px`): the other cards of a shelf. */
    const val SMALL_CARD_DP = 112f

    /** `.chart2` (`v2.js:73` `--r:20px`): a chart card. */
    const val CHART_CARD_DP = 150f

    // ---- mood tiles: a six-column grid, gap 10, a tile spans 2 columns and a big one 3 (`.moodgrid`, `.mtile`) ----
    const val MOOD_TILE_DP = 92f
    const val MOOD_TILE_BIG_DP = 116f
    const val MOOD_COLUMNS = 6
    const val MOOD_GAP_DP = 10f
    const val MOOD_MARGIN_DP = 16f

    /** How many of the first moods are big: the board draws two. */
    const val MOOD_BIG_COUNT = 2
    const val MOOD_SPAN = 2
    const val MOOD_BIG_SPAN = 3

    /**
     * The radius of a card cover by its width: 22 from 160 dp up, 20 from 140, 18 from 120, otherwise 16. The boards' inline
     * radii are the three widths they draw: lead 168 -> 22, chart 150 -> 20, small 112 -> 16 (`v2.js:64, 73`).
     */
    fun cardRadiusDp(widthDp: Float): Float = when {
        widthDp >= 160f -> 22f
        widthDp >= 140f -> 20f
        widthDp >= 120f -> 18f
        else -> 16f
    }

    // ---- the current row (`.mrow.is-playing`, `--row-on`): drawn 8 dp in from each side, radius 18, fading from left to right ----
    const val ROW_WASH_INSET_DP = 8f
    const val ROW_WASH_RADIUS_DP = 18f
    const val ROW_WASH_START_ALPHA = 0.7f
    const val ROW_WASH_MID_ALPHA = 0.2f
    const val ROW_WASH_MID_STOP = 0.75f

    // ---- the equaliser (`.eq`): three 3 dp bars, 2 dp apart, 14 dp tall, radius 2; rest heights 60, 100 and 40 per cent ----
    const val EQ_HEIGHT_DP = 14f
    const val EQ_BAR_DP = 3f
    const val EQ_GAP_DP = 2f
    const val EQ_RADIUS_DP = 2f
    private val EQ_REST = floatArrayOf(0.6f, 1f, 0.4f)
    private val EQ_PEAK = floatArrayOf(1f, 0.45f, 0.9f)

    /** The rest height of bar [index] (0..2) as a fraction of [EQ_HEIGHT_DP]: what a paused or reduced-motion row shows. */
    fun equaliserRest(index: Int): Float = EQ_REST[index.coerceIn(0, 2)]

    /** The other end of bar [index]'s bounce: the bars move between their rest height and this one while the song plays. */
    fun equaliserPeak(index: Int): Float = EQ_PEAK[index.coerceIn(0, 2)]

    // ---- the swipe on a song row: queue at 45 per cent of the width, the spring's threshold at 50, the tick re-arms below 80 of the arming point ----
    const val SWIPE_COMMIT = 0.45f
    const val SWIPE_POSITIONAL_THRESHOLD = 0.5f
    const val SWIPE_REARM = 0.8f

    /** `.swipe`: a radius-20 container in ink at 5 per cent under the row, with 72 dp action blocks of radius 16, 6 dp in from its edge. */
    const val SWIPE_CONTAINER_RADIUS_DP = 20f
    const val SWIPE_CONTAINER_INK = 0.05f
    const val SWIPE_ACTION_DP = 72f
    const val SWIPE_ACTION_RADIUS_DP = 16f
    const val SWIPE_ACTION_INSET_DP = 6f

    // ---- the mood chip (`.mood2`, spec "Mood chip (44 ...)"): radius k 0.30 at rest, 0.5 selected, 0.20 pressed, squished to 96.5 per cent of its width ----
    const val MOOD_CHIP_HEIGHT_DP = 44f
    const val MOOD_CHIP_REST_K = 0.30f
    const val MOOD_CHIP_ON_K = 0.5f
    const val MOOD_CHIP_PRESSED_K = 0.20f
    const val MOOD_PRESSED_SCALE_X = 0.965f

    /** Where a mood chip is heading: a pill (exponent 2) when selected, otherwise a squircle; pressed it squares up and squishes sideways. */
    fun moodChipTarget(selected: Boolean, pressed: Boolean): MorphTarget = when {
        pressed -> MorphTarget(MOOD_CHIP_PRESSED_K * MOOD_CHIP_HEIGHT_DP, 4f, MOOD_PRESSED_SCALE_X, 1f)
        selected -> MorphTarget(MOOD_CHIP_ON_K * MOOD_CHIP_HEIGHT_DP, 2f, 1f)
        else -> MorphTarget(MOOD_CHIP_REST_K * MOOD_CHIP_HEIGHT_DP, 4f, 1f)
    }

    /** A mood tile (`.mtile`): radius 20; pressed it takes the chip's press (the radius shrinks by the chip's 0.20 / 0.30 and it squishes the same). */
    const val MOOD_TILE_RADIUS_DP = 20f
    fun moodTilePressedRadiusDp(): Float = MOOD_TILE_RADIUS_DP * MOOD_CHIP_PRESSED_K / MOOD_CHIP_REST_K

    fun moodTileTarget(pressed: Boolean): MorphTarget =
        if (pressed) MorphTarget(moodTilePressedRadiusDp(), 4f, MOOD_PRESSED_SCALE_X, 1f) else MorphTarget(MOOD_TILE_RADIUS_DP, 4f, 1f)

    // ---- the liked tile and the mix tile (`.liked2`, `.mix2` in a pinned pair): 172 dp tall, radius 26; a press only shrinks them a little ----
    const val TILE_HEIGHT_DP = 172f
    const val TILE_RADIUS_DP = 26f
    const val TILE_PRESSED_SCALE = 0.98f

    fun tileTarget(pressed: Boolean): MorphTarget = MorphTarget(TILE_RADIUS_DP, 4f, if (pressed) TILE_PRESSED_SCALE else 1f)

    fun moodTileHeightDp(big: Boolean): Float = if (big) MOOD_TILE_BIG_DP else MOOD_TILE_DP

    /**
     * The width of a mood tile in a window [windowWidthDp] wide: the content width less five gaps, in six columns; a tile spans
     * two columns and the gap between them, a big one three and two gaps.
     */
    fun moodTileWidthDp(windowWidthDp: Float, big: Boolean): Float {
        val column = (windowWidthDp - 2 * MOOD_MARGIN_DP - (MOOD_COLUMNS - 1) * MOOD_GAP_DP) / MOOD_COLUMNS
        val span = if (big) MOOD_BIG_SPAN else MOOD_SPAN
        return span * column + (span - 1) * MOOD_GAP_DP
    }

    /**
     * One row of the mood grid: the moods from [first] on, [size] of them, [big] or not. A row has [slots] places (two big tiles
     * or three small ones); a row that is not full leaves its last places empty, so a lone tile keeps the width of a full row's.
     */
    data class MoodRow(val first: Int, val size: Int, val big: Boolean) {
        val slots: Int get() = if (big) MOOD_COLUMNS / MOOD_BIG_SPAN else MOOD_COLUMNS / MOOD_SPAN
    }

    /** The rows of [count] moods: the first two big, two to a row; the rest three to a row. */
    fun moodRows(count: Int): List<MoodRow> {
        if (count <= 0) return emptyList()
        val rows = ArrayList<MoodRow>()
        val bigSize = minOf(MOOD_BIG_COUNT, count)
        rows += MoodRow(first = 0, size = bigSize, big = true)
        var next = bigSize
        val perRow = MOOD_COLUMNS / MOOD_SPAN
        while (next < count) {
            val size = minOf(perRow, count - next)
            rows += MoodRow(first = next, size = size, big = false)
            next += size
        }
        return rows
    }
}
