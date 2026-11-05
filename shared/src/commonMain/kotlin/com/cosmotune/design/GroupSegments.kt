package com.cosmotune.design

import kotlin.math.floor

/**
 * The tiles of a grouped list of settings (`components-v3bc.css:135-137`: `.grp { gap: 3px }`, the first tile's top and the last
 * tile's bottom corners 24, every other corner 6). The rows of a group are laid out in one column; the thin gaps between
 * tiles are where a divider stood, and [of] turns the gaps into the rectangles between them.
 *
 * Positions and radii are in one unit (px for drawing, dp where a caller works in dp). A superellipse corner reads smaller than
 * a circular one of the same radius, and Compose draws the circular one, so a drawn radius is the designed one over 1.3
 * ([drawnRadius]: 24 becomes 18 and 6 becomes 5; `02-design-formulas.md` section 3).
 */
object GroupSegments {
    /** The space between two tiles. */
    const val GAP_DP = 3f
    const val OUTER_RADIUS_DP = 24f
    const val INNER_RADIUS_DP = 6f
    private const val SUPERELLIPSE_SHRINK = 1.3f

    /** One tile: [top] and [bottom] are where it starts and ends; the radii are those of its upper and its lower pair of corners. */
    data class Segment(val top: Float, val bottom: Float, val topRadius: Float, val bottomRadius: Float)

    /**
     * The tiles of a group [totalHeight] tall whose gaps are [gaps] (any order; overlapping or touching gaps are one gap; a part
     * of a gap outside the group is cut off). A group with no gap is one tile, [outer] on all four corners; otherwise the first
     * tile is [outer] above and [inner] below, the last [inner] above and [outer] below, and every tile between [inner] all round.
     * A gap at an end of the group leaves no empty tile there.
     */
    fun of(
        totalHeight: Float,
        gaps: List<ClosedFloatingPointRange<Float>>,
        outer: Float = OUTER_RADIUS_DP,
        inner: Float = INNER_RADIUS_DP,
    ): List<Segment> {
        if (!(totalHeight > 0f)) return emptyList()
        val bounds = ArrayList<FloatArray>(gaps.size + 1)
        var cursor = 0f
        for (gap in gaps.sortedBy { it.start }) {
            val start = gap.start.coerceIn(0f, totalHeight)
            val end = gap.endInclusive.coerceIn(0f, totalHeight)
            if (end <= start) continue
            if (start > cursor) bounds.add(floatArrayOf(cursor, start))
            if (end > cursor) cursor = end
        }
        if (cursor < totalHeight) bounds.add(floatArrayOf(cursor, totalHeight))
        val last = bounds.lastIndex
        return bounds.mapIndexed { i, b ->
            Segment(
                top = b[0],
                bottom = b[1],
                topRadius = if (i == 0) outer else inner,
                bottomRadius = if (i == last) outer else inner,
            )
        }
    }

    /** The circular radius that stands in for a superellipse corner of [radius]: `round(radius / 1.3)`. */
    fun drawnRadius(radius: Float): Float = floor(radius / SUPERELLIPSE_SHRINK + 0.5f)
}
