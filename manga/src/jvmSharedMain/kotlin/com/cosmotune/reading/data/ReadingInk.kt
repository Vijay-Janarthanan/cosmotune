package com.cosmotune.reading.data

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/** A pen for drawing on PDF pages: its colour (ARGB), line width (a share of the page's width) and whether it is a see-through marker. */
@Serializable enum class ReadingInk(val label: String, val argb: Long, val width: Float, val marker: Boolean = false) {
    RED("Red", 0xFFD32F2F, 0.004f), BLUE("Blue", 0xFF1E5BD8, 0.004f), BLACK("Black", 0xFF1B1B1B, 0.003f),
    MARKER("Marker", 0x66FFD400, 0.018f, marker = true)
}

/** One line drawn on a PDF page: [points] are x, y pairs, each a share (0 to 1) of the whole page's width and height. */
@Serializable data class ReadingInkStroke(val id: String, val bookId: String, val page: Int, val ink: ReadingInk, val points: List<Float>,
    val createdAtMs: Long = System.currentTimeMillis())

internal object ReadingInkMath {
    const val MAX_POINTS = 2_000
    const val MAX_STROKES = 5_000

    /** [points] (x, y pairs) without the ones closer than [minimum] to the last kept, rounded to four places, at most [MAX_POINTS]. */
    fun simplify(points: List<Float>, minimum: Float = 0.002f): List<Float> {
        val kept = ArrayList<Float>()
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i].coerceIn(0f, 1f); val y = points[i + 1].coerceIn(0f, 1f)
            val last = kept.size >= 2
            val isEnd = i + 3 >= points.size
            if (!last || isEnd || hypot(x - kept[kept.size - 2], y - kept[kept.size - 1]) >= minimum) { kept += round(x); kept += round(y) }
            i += 2
        }
        if (kept.size > MAX_POINTS * 2) {
            val step = kept.size / 2 / MAX_POINTS + 1
            return kept.chunked(2).filterIndexed { index, _ -> index % step == 0 }.flatten()
        }
        return kept
    }

    /** Whether a touch at ([x], [y]) (page shares) is within [tolerance] of [stroke]'s line. */
    fun hits(stroke: ReadingInkStroke, x: Float, y: Float, tolerance: Float = 0.02f): Boolean {
        val p = stroke.points
        if (p.size == 2) return hypot(p[0] - x, p[1] - y) <= tolerance
        var i = 0
        while (i + 3 < p.size) {
            if (segment(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= tolerance + stroke.ink.width / 2) return true
            i += 2
        }
        return false
    }

    private fun segment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / length).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun round(value: Float) = kotlin.math.round(value * 10_000f) / 10_000f
}
