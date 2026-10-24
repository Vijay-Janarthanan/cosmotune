package com.cosmotune.music.wear.ui

import kotlin.math.abs

/**
 * Turns rotary scroll events into whole steps, the same for a notched bezel
 * and a smooth crown.
 *
 * A notched bezel (Galaxy Watch Classic) reports one large event per detent; a
 * smooth crown reports a stream of small ones. A large event is exactly one
 * step in its direction and drops whatever partial travel was pending; small
 * events add up until they make [stepPx] of travel. On the Now Playing screen
 * each step is one volume step, or five seconds while the disc is scrubbing.
 */
class RotaryDetents(private val stepPx: Float = DEFAULT_STEP_PX) {

    private var pending = 0f

    /** The whole steps one event makes (negative for the other way, 0 for none yet). */
    fun steps(verticalScrollPx: Float): Int {
        if (verticalScrollPx.isNaN() || verticalScrollPx == 0f) return 0
        if (abs(verticalScrollPx) >= stepPx) {
            pending = 0f
            return if (verticalScrollPx > 0) 1 else -1
        }
        pending += verticalScrollPx
        val whole = (pending / stepPx).toInt()
        pending -= whole * stepPx
        return whole
    }

    companion object {
        /** Crown travel per step, for crowns that report a stream of small events. */
        const val DEFAULT_STEP_PX = 48f
    }
}

/** Where a hold-to-seek target lands after [deltaMs], kept inside the track. */
fun scrubTarget(fromMs: Long, deltaMs: Long, durationMs: Long): Long =
    (fromMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
