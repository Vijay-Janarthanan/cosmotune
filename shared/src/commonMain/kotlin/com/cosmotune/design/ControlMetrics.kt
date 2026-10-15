package com.cosmotune.design

/**
 * Sizes, paddings, type steps and highlight opacities of the morphing controls (buttons, icon and play buttons, chips,
 * segmented control, switch), from `v3/01-components-spec-bc.md` and `components-v3bc.css`. Shape numbers (radius, exponent,
 * press scale) are [ControlMorph]'s; the colours are the cover's. Every number here is named, so a change of the design is a
 * change of one constant.
 */
object ControlMetrics {
    // ---- buttons: 40 / 48 / 56 dp, padding 0.46 h, label 14 / 15 / 16 sp (weight 680), icon 20 / 20 / 22 dp, 8 dp between ----
    const val BUTTON_SMALL_DP = 40f
    const val BUTTON_MEDIUM_DP = 48f
    const val BUTTON_LARGE_DP = 56f
    const val BUTTON_PADDING_K = 0.46f

    /** A text button has no fill to pad: 12 dp each side (`.b-text`). */
    const val TEXT_BUTTON_PADDING_DP = 12f
    const val BUTTON_GAP_DP = 8f

    fun buttonPaddingDp(heightDp: Float): Float = BUTTON_PADDING_K * heightDp
    fun buttonFontSp(heightDp: Float): Float = if (heightDp >= BUTTON_LARGE_DP) 16f else if (heightDp <= BUTTON_SMALL_DP) 14f else 15f
    fun buttonIconDp(heightDp: Float): Float = if (heightDp >= BUTTON_LARGE_DP) 22f else 20f

    // ---- icon buttons: 40 / 48 / 56 dp with 20 / 24 / 28 dp icons ----
    fun iconButtonIconDp(sizeDp: Float): Float = if (sizeDp < 44f) 20f else if (sizeDp < 52f) 24f else 28f

    // ---- the hero play button: icon 0.4 of the button, the loading blob 0.32 ----
    const val PLAY_ICON_K = 0.4f
    const val PLAY_BLOB_K = 0.32f
    fun playIconDp(sizeDp: Float): Float = PLAY_ICON_K * sizeDp
    fun playBlobDp(sizeDp: Float): Float = PLAY_BLOB_K * sizeDp

    /** The ring of a C play button's loading state: a 3 dp arc, 6 dp outside the button. */
    const val C_PLAY_ARC_DP = 3f
    const val C_PLAY_ARC_GAP_DP = 6f

    // ---- filter chips: 36 dp (big 44), padding 14, label 14 sp (big 15), check 18 dp, 8 dp between ----
    const val CHIP_HEIGHT_DP = 36f
    const val CHIP_BIG_HEIGHT_DP = 44f
    const val CHIP_PADDING_DP = 14f
    const val CHIP_GAP_DP = 8f
    const val CHIP_ICON_DP = 18f
    fun chipHeightDp(big: Boolean): Float = if (big) CHIP_BIG_HEIGHT_DP else CHIP_HEIGHT_DP
    fun chipFontSp(big: Boolean): Float = if (big) 15f else 14f

    // ---- segmented control: 40 dp segments 3 dp apart, padding 16, label 14 sp, check 18 dp; outer ends are pills ----
    const val SEGMENT_HEIGHT_DP = 40f
    const val SEGMENT_GAP_DP = 3f
    const val SEGMENT_PADDING_DP = 16f
    const val SEGMENT_OUTER_RADIUS_DP = 20f
    const val SEGMENT_ICON_DP = 18f

    /**
     * A segmented control that fills its row (`fill`, Library's four segments; `.row2.stack .seg2 > button { flex: 1 }`): the segments
     * share the width ([fillSegmentWidthDp]), with [SEGMENT_FILL_PADDING_DP] inside each end instead of 16, their labels shrink to fit
     * down to [SEGMENT_LABEL_MIN_SP], and the check shows only up to [SEGMENT_FILL_CHECK_MAX] options ([segmentShowsCheck]), so four
     * labels fit a 360 dp phone without scrolling sideways. The three-segment controls elsewhere do not fill and are unchanged.
     */
    const val SEGMENT_FILL_PADDING_DP = 4f
    const val SEGMENT_LABEL_MIN_SP = 12f
    const val SEGMENT_FILL_CHECK_MAX = 3

    /** The width of each of [count] segments sharing a row [rowDp] wide, [SEGMENT_GAP_DP] apart. */
    fun fillSegmentWidthDp(rowDp: Float, count: Int): Float = (rowDp - SEGMENT_GAP_DP * (count - 1)) / count

    /** Whether the selected segment shows its check: always in the classic control, up to three options in a filling one. */
    fun segmentShowsCheck(count: Int, fill: Boolean): Boolean = !fill || count <= SEGMENT_FILL_CHECK_MAX

    /** The gap between a segment's check and its label. */
    const val SEGMENT_CHECK_GAP_DP = 6f

    /**
     * A settings page's segmented control (theme, output precision, refresh rate, colour intensity) always shares its row, so it takes
     * the filling control's numbers: [SEGMENT_FILL_PADDING_DP] inside each end, the check only on the selected segment of a full-size
     * control of up to [SEGMENT_FILL_CHECK_MAX] options, and a label that shrinks from [settingsSegmentLabelMaxSp] to
     * [SEGMENT_LABEL_MIN_SP] before it is cut. With the board's 12 and 16 dp ends, "Balanced" (Intensity) was cut on a 360 dp phone.
     */
    fun settingsSegmentLabelRoomDp(rowDp: Float, count: Int, check: Boolean): Float =
        fillSegmentWidthDp(rowDp, count) - 2 * SEGMENT_FILL_PADDING_DP - (if (check) SEGMENT_ICON_DP + SEGMENT_CHECK_GAP_DP else 0f)

    fun settingsSegmentShowsCheck(selected: Boolean, compact: Boolean, count: Int): Boolean =
        selected && !compact && segmentShowsCheck(count, fill = true)

    /** The label's own size: 14 sp, 13 in the small control that stands under a row (`.mini-seg`). */
    fun settingsSegmentLabelMaxSp(compact: Boolean): Float = if (compact) 13f else 14f

    /** The C segmented control: a glass track (4 dp padding, radius 24) around a 32 dp ink thumb that slides. */
    const val C_SEGMENT_TRACK_PADDING_DP = 4f
    const val C_SEGMENT_TRACK_RADIUS_DP = 24f
    const val C_SEGMENT_THUMB_DP = 32f

    /**
     * The four corner radii of segment [index] of [count], written to [out] as top-left, top-right, bottom-right,
     * bottom-left. The corners on the outside of the group stay a pill ([SEGMENT_OUTER_RADIUS_DP]); the ones that face a
     * neighbour take [innerRadiusDp], which the press morph animates (6 at rest, a full 20 when the segment is selected).
     */
    fun segmentCorners(index: Int, count: Int, innerRadiusDp: Float, out: FloatArray, outerRadiusDp: Float = SEGMENT_OUTER_RADIUS_DP) {
        val left = if (index == 0) outerRadiusDp else innerRadiusDp
        val right = if (index == count - 1) outerRadiusDp else innerRadiusDp
        out[0] = left; out[1] = right; out[2] = right; out[3] = left
    }

    // ---- switch: a 52 x 32 dp track; the thumb is 16 dp at (8, 8) when off and 24 dp at (24, 4) when on ----
    const val SWITCH_WIDTH_DP = 52f
    const val SWITCH_HEIGHT_DP = 32f
    const val SWITCH_THUMB_OFF_DP = 16f
    const val SWITCH_THUMB_ON_DP = 24f
    const val SWITCH_THUMB_OFF_X_DP = 8f
    const val SWITCH_THUMB_ON_X_DP = 24f
    const val SWITCH_THUMB_OFF_Y_DP = 8f
    const val SWITCH_THUMB_ON_Y_DP = 4f

    /** The off-state ring: 1.5 dp of `textTertiary` (`.sw2`), and the check in the on thumb is 16 dp with a 2.4 dp stroke at 24 units. */
    const val SWITCH_RING_DP = 1.5f
    const val SWITCH_CHECK_DP = 16f
    const val SWITCH_CHECK_STROKE_UNITS = 2.4f

    /** Writes the thumb at [p] (0 off, 1 on; the spring may overshoot) as diameter, x and y of its top-left corner, in dp. */
    fun switchThumb(p: Float, out: FloatArray) {
        out[0] = SWITCH_THUMB_OFF_DP + (SWITCH_THUMB_ON_DP - SWITCH_THUMB_OFF_DP) * p
        out[1] = SWITCH_THUMB_OFF_X_DP + (SWITCH_THUMB_ON_X_DP - SWITCH_THUMB_OFF_X_DP) * p
        out[2] = SWITCH_THUMB_OFF_Y_DP + (SWITCH_THUMB_ON_Y_DP - SWITCH_THUMB_OFF_Y_DP) * p
    }

    // ---- fills and highlights (opacity of the colour laid over a fill) ----
    /** Quiet button: `textPrimary` at 9 %. A rest chip, segment and switch track: 7 %, 7 % and 12 %. */
    const val QUIET_FILL = 0.09f
    const val CHIP_REST_FILL = 0.07f
    const val SEGMENT_REST_FILL = 0.07f
    const val SWITCH_TRACK_FILL = 0.12f

    /** The 1 dp ring around a rest chip or segment: `outline`, which is `textPrimary` at 14 %. */
    const val OUTLINE = 0.14f

    /** The hairline at the top of a primary button: white 10 % (light theme 18 %); a tonal one 6 %; the hero play button 22 %. */
    fun highlightPrimary(dark: Boolean): Float = if (dark) 0.10f else 0.18f
    const val HIGHLIGHT_TONAL = 0.06f
    const val HIGHLIGHT_PLAY = 0.22f

    /** The soft cover gradients (B): how far the second stop of a primary / secondary / icon button moves towards the other colour. */
    const val PRIMARY_END_MIX = 0.5f
    const val TONAL_START_MIX = 0.09f
    const val ICON_START_MIX = 0.05f

    // ---- C: floating controls ----
    /** A C primary button is 46 % cover colour over the glass (a play button 72 %), with a 1.5 dp gradient border. */
    const val C_PRIMARY_COLOUR_SHARE = 0.46f
    const val C_PLAY_COLOUR_SHARE = 0.72f
    const val C_PRIMARY_BORDER_DP = 1.5f
    const val C_PRIMARY_BORDER_WHITE_MIX = 0.25f
    const val C_PLAY_RING_DP = 1.5f
    const val C_PLAY_RING_WHITE_MIX = 0.30f

    /** The inner highlight at the top: a C primary button 28 %, a C play button 30 % (2 dp), a glass control 22 % (light theme 80 %). */
    const val C_PRIMARY_TOP_HIGHLIGHT = 0.28f
    const val C_PLAY_TOP_HIGHLIGHT = 0.30f
    const val C_PLAY_TOP_HIGHLIGHT_DP = 2f
    fun glassTopHighlight(dark: Boolean): Float = if (dark) 0.22f else 0.80f

    /** The 1 dp ring of a glass control: white 14 % in the dark theme, black 10 % in the light one. */
    fun glassRing(dark: Boolean): Float = if (dark) 0.14f else 0.10f

    /** The same ring on a secondary C button, which the spec draws a little brighter in the dark theme (16 %). */
    fun glassRingSecondary(dark: Boolean): Float = if (dark) 0.16f else 0.10f

    /** What a pressed C primary button lays over itself: its end colour at 18 %. */
    const val C_PRIMARY_PRESSED_LAYER = 0.18f
}
