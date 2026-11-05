package com.cosmotune.design

/**
 * The bento grid of Home (`02-design-formulas.md` section 8; `screens-v2.css` `.bento2`; `screens-v3.css` `.dh-bento`). Pure; sizes
 * are dp as floats, like [BlockMetrics].
 *
 * A page [windowDp] wide with [columns] columns, a [marginDp] at each side and [gutterDp] between columns has cells of
 * `cw = (W - 2m - (cols - 1) g) / cols`; a span of `n` columns is `n cw + (n - 1) g` wide, and a span of `r` rows of a row unit `u` is
 * `r u + (r - 1) g` tall. The worked example of the formulas file is a 390 dp phone: 4 columns, margin 16, gutter 12, so `cw` is 80.5, the
 * hero (4 x 2 rows of 80) is 358 x 172 and a mix (2 x 2) is 173 x 172.
 */
object Bento {

    /** The width of one cell. A window too narrow for its margins and gutters has cells of 0, never a negative width. */
    fun cellWidth(windowDp: Float, columns: Int, marginDp: Float, gutterDp: Float): Float {
        if (columns <= 0) return 0f
        return ((windowDp - 2f * marginDp - (columns - 1) * gutterDp) / columns).coerceAtLeast(0f)
    }

    /** The width of [span] columns and the gutters between them; 0 for an empty span. */
    fun spanWidth(span: Int, windowDp: Float, columns: Int, marginDp: Float, gutterDp: Float): Float {
        if (span <= 0) return 0f
        return span * cellWidth(windowDp, columns, marginDp, gutterDp) + (span - 1) * gutterDp
    }

    /** The height of [rows] rows of [unitDp] and the gutters between them; 0 for no rows. */
    fun spanHeight(rows: Int, unitDp: Float, gutterDp: Float): Float {
        if (rows <= 0) return 0f
        return rows * unitDp + (rows - 1) * gutterDp
    }

    /**
     * One grid of Home: its columns, margin, gutter and row unit, and how much of it the hero and a mix take. The sizes are of a
     * page [windowDp] wide (the page, not the screen: on the desktop the sidebar is not part of it).
     */
    class Grid(
        val columns: Int,
        val marginDp: Float,
        val gutterDp: Float,
        val unitDp: Float,
        val heroColumns: Int,
        val heroRows: Int,
        val mixColumns: Int,
        val mixRows: Int,
    ) {
        fun cellWidth(windowDp: Float): Float = cellWidth(windowDp, columns, marginDp, gutterDp)
        fun heroWidth(windowDp: Float): Float = spanWidth(heroColumns, windowDp, columns, marginDp, gutterDp)
        fun heroHeight(): Float = spanHeight(heroRows, unitDp, gutterDp)
        fun mixWidth(windowDp: Float): Float = spanWidth(mixColumns, windowDp, columns, marginDp, gutterDp)
        fun mixHeight(): Float = spanHeight(mixRows, unitDp, gutterDp)
    }

    /** `.bento2`: 4 columns, rows of 80, gap 12, margin 16; the hero takes all four columns and two rows, a mix two and two. */
    val PHONE = Grid(columns = 4, marginDp = 16f, gutterDp = 12f, unitDp = 80f, heroColumns = 4, heroRows = 2, mixColumns = 2, mixRows = 2)

    /** `.dh-bento` (`screens-v3.css:300-302`): 12 columns, rows of 104, gap 16, margin 28; the hero 7 x 4, a mix 5 x 2. */
    val WIDE = Grid(columns = 12, marginDp = 28f, gutterDp = 16f, unitDp = 104f, heroColumns = 7, heroRows = 4, mixColumns = 5, mixRows = 2)

    /** A page this wide or wider (the desktop, a tablet in landscape) gets the [WIDE] grid. */
    const val WIDE_MIN_DP = 840f

    /** The grid of a page [pageDp] wide. */
    fun gridFor(pageDp: Float): Grid = if (pageDp >= WIDE_MIN_DP) WIDE else PHONE
}
