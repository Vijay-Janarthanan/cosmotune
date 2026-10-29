package com.cosmotune.video.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle on the desktop in screen pixels: its top left corner and its size. */
data class PipBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** The corner of the picture-in-picture window a drag holds. */
enum class PipCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false),
}

/**
 * The geometry of the desktop's picture-in-picture window (pure): where it first opens, how it resizes while it keeps the video's
 * shape, how it is kept on a screen, and the one line of text its size and place are remembered as.
 */
object PipWindowMath {
    /** The narrowest the window gets. */
    const val MIN_WIDTH = 240

    /** The width it opens at the first time. */
    const val DEFAULT_WIDTH = 480

    /** The gap to the screen's corner when it first opens. */
    const val MARGIN = 24

    /** The pixels of the video's shape a window of [width] has for height, never below 1. */
    fun heightFor(width: Int, aspect: Float): Int = if (aspect > 0f) max(1, (width / aspect).roundToInt()) else max(1, width * 9 / 16)

    /** The first bounds: [DEFAULT_WIDTH] wide (less on a small screen), in the bottom right corner of [screen]. */
    fun initial(aspect: Float, screen: PipBounds): PipBounds {
        val width = min(DEFAULT_WIDTH, max(MIN_WIDTH, screen.width / 3))
        val height = heightFor(width, aspect)
        return PipBounds(screen.right - width - MARGIN, screen.bottom - height - MARGIN, width, height)
    }

    /** [bounds] with the height that fits [aspect] for its width, the top left corner kept. */
    fun reshaped(bounds: PipBounds, aspect: Float): PipBounds = bounds.copy(height = heightFor(bounds.width, aspect))

    /**
     * [start] after the [corner] was dragged by [dx] and [dy] pixels: the width follows the drag (a left corner dragged right is
     * narrower), within [MIN_WIDTH] and [maxWidth]; the height follows from [aspect]; the opposite corner stays where it was.
     */
    fun resized(start: PipBounds, corner: PipCorner, dx: Int, dy: Int, aspect: Float, maxWidth: Int): PipBounds {
        val byWidth = start.width + (if (corner.left) -dx else dx)
        // A drag mostly up or down resizes by the height it implies.
        val byHeight = ((start.height + (if (corner.top) -dy else dy)) * (if (aspect > 0f) aspect else 16f / 9f)).roundToInt()
        val wanted = if (abs(dy) * aspectOrOne(aspect) > abs(dx)) byHeight else byWidth
        val width = wanted.coerceIn(MIN_WIDTH, max(MIN_WIDTH, maxWidth))
        val height = heightFor(width, aspect)
        val x = if (corner.left) start.right - width else start.x
        val y = if (corner.top) start.bottom - height else start.y
        return PipBounds(x, y, width, height)
    }

    /**
     * [bounds] moved (and made smaller when it must) to lie inside the screen of [screens] it overlaps most, or the first when it
     * overlaps none: a window remembered on a monitor that was unplugged comes back to the one that is there.
     */
    fun onScreen(bounds: PipBounds, screens: List<PipBounds>): PipBounds {
        val screen = screens.maxByOrNull { overlap(bounds, it) }?.takeIf { overlap(bounds, it) > 0L } ?: screens.firstOrNull() ?: return bounds
        val width = min(bounds.width, screen.width)
        val height = min(bounds.height, screen.height)
        val x = bounds.x.coerceIn(screen.x, max(screen.x, screen.right - width))
        val y = bounds.y.coerceIn(screen.y, max(screen.y, screen.bottom - height))
        return PipBounds(x, y, width, height)
    }

    /** The bounds as `x,y,width,height`. */
    fun encode(bounds: PipBounds): String = "${bounds.x},${bounds.y},${bounds.width},${bounds.height}"

    /** The bounds [encode] wrote, or null for text that is not one (or a size below [MIN_WIDTH]). */
    fun decode(text: String): PipBounds? {
        val parts = text.trim().split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 4 || parts[2] < MIN_WIDTH || parts[3] < 1) return null
        return PipBounds(parts[0], parts[1], parts[2], parts[3])
    }

    private fun aspectOrOne(aspect: Float) = if (aspect > 0f) aspect else 1f

    private fun overlap(a: PipBounds, b: PipBounds): Long {
        val width = min(a.right, b.right) - max(a.x, b.x)
        val height = min(a.bottom, b.bottom) - max(a.y, b.y)
        return if (width <= 0 || height <= 0) 0L else width.toLong() * height
    }
}
