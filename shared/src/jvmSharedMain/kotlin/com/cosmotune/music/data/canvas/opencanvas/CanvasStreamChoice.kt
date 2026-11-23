package com.opencanvas.core.sync

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Which stream of a music video is worth playing for the box it is drawn in. A video fills its box (cropped), so the side that
 * overflows sets how tall the picture is drawn, in real pixels (the display's scale included). A stream shorter than that is
 * drawn blown up and looks soft; one taller than the user's quality setting allows is never asked for.
 */
object CanvasStreamChoice {
    /** The stream heights YouTube serves for a music video, shortest first. */
    val HEIGHTS = listOf(360, 480, 720, 1080)

    /** How tall a [sourceWidth]×[sourceHeight] picture is drawn when it fills a [viewWidthPx]×[viewHeightPx] box. */
    fun drawnHeight(viewWidthPx: Int, viewHeightPx: Int, sourceWidth: Int, sourceHeight: Int): Int {
        if (viewWidthPx <= 0 || viewHeightPx <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return 0
        val scale = max(viewWidthPx.toDouble() / sourceWidth, viewHeightPx.toDouble() / sourceHeight)
        return (sourceHeight * scale).roundToInt()
    }

    /**
     * The stream height to switch to, or null when the one playing will do: the shortest standard height that covers the drawn
     * picture, never above [capHeight] (the quality setting), asked for only when the playing stream ([sourceHeight]) is
     * clearly shorter than that (a fast-start 360p copy under a large box, say).
     */
    fun upgradeHeight(viewWidthPx: Int, viewHeightPx: Int, sourceWidth: Int, sourceHeight: Int, capHeight: Int): Int? {
        val drawn = drawnHeight(viewWidthPx, viewHeightPx, sourceWidth, sourceHeight)
        if (drawn <= 0 || capHeight <= 0) return null
        val wanted = HEIGHTS.firstOrNull { it >= drawn * ENOUGH } ?: HEIGHTS.last()
        val target = minOf(wanted, capHeight)
        return target.takeIf { sourceHeight < target * ENOUGH }
    }

    /**
     * The stream height a phone asks for, before anything plays: a music video across a portrait phone, or full screen
     * sideways, is drawn as tall as the screen's short side ([shortSidePx]), so the shortest standard height that covers it,
     * never above [capHeight] (the quality setting). An unknown screen keeps the setting.
     */
    fun forScreen(shortSidePx: Int, capHeight: Int): Int {
        if (shortSidePx <= 0) return capHeight
        val wanted = HEIGHTS.firstOrNull { it >= shortSidePx * ENOUGH } ?: HEIGHTS.last()
        return minOf(wanted, capHeight)
    }

    /** A stream within this share of the wanted height is as good as it: 1072p is 1080p, a 676p widescreen copy is 720p. */
    private const val ENOUGH = 0.9
}
