package com.cosmotune.design

import kotlin.math.max
import kotlin.math.min

/** Fixed Music artwork framing. Full Video mode and Shorts keep their own source aspect. */
object NowPlayingVideoMetrics {
    const val ASPECT = 4f / 5f
    const val MAX_PHONE_HEIGHT_FRACTION = 0.60f
    const val PHONE_SIDE_DP = 16f
    const val PHONE_HEADER_DP = 76f
    const val CONTROLS_HANDLE_DP = 48f
    const val CONTROLS_GAP_DP = 16f

    data class Frame(val x: Float, val y: Float, val width: Float, val height: Float)

    fun fit(width: Float, height: Float): Frame {
        val w = width.takeIf { it.isFinite() && it > 0f } ?: 0f
        val h = height.takeIf { it.isFinite() && it > 0f } ?: 0f
        val frameHeight = min(h, w / ASPECT)
        val frameWidth = min(w, frameHeight * ASPECT)
        return Frame((w - frameWidth) / 2f, (h - frameHeight) / 2f, frameWidth, frameHeight)
    }

    /** The expanded controls are reserved even when minimized, so dragging them never changes the crop. */
    fun portrait(width: Float, height: Float, controlsHeight: Float, topInset: Float): Frame {
        val w = width.takeIf { it.isFinite() && it > 0f } ?: 0f
        val h = height.takeIf { it.isFinite() && it > 0f } ?: 0f
        val top = min(h, max(0f, topInset) + PHONE_HEADER_DP)
        val room = max(0f, h - top - max(0f, controlsHeight) - CONTROLS_HANDLE_DP - CONTROLS_GAP_DP)
        val frame = fit(max(0f, w - 2f * PHONE_SIDE_DP), min(room, h * MAX_PHONE_HEIGHT_FRACTION))
        return Frame((w - frame.width) / 2f, top + (room - frame.height) / 2f, frame.width, frame.height)
    }
}
