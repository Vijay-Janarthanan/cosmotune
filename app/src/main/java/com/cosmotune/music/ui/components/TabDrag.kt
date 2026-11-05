package com.cosmotune.music.ui.components

import kotlin.math.roundToInt

/**
 * Where a dragged tab-bar indicator is (BC-34, upstream `21e7080c`, `fbca2d28`, `5bd7b905`, `5396c0cd`): in absolute tab
 * positions counted from the tab the drag started on, held between the first and the last tab. Relative to the tab selected
 * now, as the bars had it, a selection that moved during the drag was counted twice and the indicator ran past either end.
 */
internal object TabDrag {
    /** The indicator's position, in tabs, [totalDragPx] into a drag that started on [startIndex], each tab [stepPx] wide. */
    fun position(startIndex: Int, totalDragPx: Float, stepPx: Float, lastIndex: Int): Float {
        val last = lastIndex.coerceAtLeast(0).toFloat()
        if (stepPx <= 0f || stepPx.isNaN()) return startIndex.toFloat().coerceIn(0f, last)
        return (startIndex + totalDragPx / stepPx).coerceIn(0f, last)
    }

    /** The tab a drag lands on when it is let go. */
    fun target(startIndex: Int, totalDragPx: Float, stepPx: Float, lastIndex: Int): Int =
        position(startIndex, totalDragPx, stepPx, lastIndex).roundToInt()
}
