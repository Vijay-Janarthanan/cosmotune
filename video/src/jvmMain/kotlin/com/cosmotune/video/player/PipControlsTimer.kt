package com.cosmotune.video.player

/**
 * When the picture-in-picture window's controls show: pure timing, no window and no clock of its own (every call is given the time in
 * milliseconds), so the rules can be tested.
 *
 * - While the pointer is over the window the controls show, and fade [IDLE_MS] after the last movement or click when the video plays
 *   (a paused video keeps them).
 * - When the pointer leaves the window (to another application, or off the screen) they go within [LEAVE_MS]; coming back at once
 *   keeps them.
 * - They never go while a settings-like surface is open or the window is being dragged or resized ([lock]); when that ends they
 *   stay a moment as after a movement.
 * - A window that has just opened shows them for [OPEN_MS], so it is clear what they are.
 */
internal class PipControlsTimer(openedAt: Long) {
    private var inside = false
    private var playing = true
    private var locks = 0
    private var activeUntil = Long.MIN_VALUE
    private var leaveUntil = openedAt + OPEN_MS

    /** Whether the controls show at [now]. */
    fun visible(now: Long): Boolean = when {
        locks > 0 -> true
        inside -> !playing || now < activeUntil
        else -> now < leaveUntil
    }

    /**
     * Milliseconds from [now] until [visible] changes by itself (no event in between), or null when it stays as it is. Re-ask after
     * every event.
     */
    fun nextChange(now: Long): Long? = when {
        locks > 0 -> null
        inside -> if (playing && now < activeUntil) activeUntil - now else null
        else -> if (now < leaveUntil) leaveUntil - now else null
    }

    /** The pointer is over the window; [moved] when it moved or clicked since the last call (it then wakes the controls for [IDLE_MS]). */
    fun pointerInside(now: Long, moved: Boolean) {
        if (!inside) {
            inside = true
            activeUntil = now + IDLE_MS
        } else if (moved) {
            activeUntil = now + IDLE_MS
        }
    }

    /** The pointer is not over the window. */
    fun pointerOutside(now: Long) {
        if (!inside) return
        inside = false
        leaveUntil = now + LEAVE_MS
    }

    /** The video plays or pauses; starting to play counts as activity (the controls then fade after [IDLE_MS], not at once). */
    fun playing(now: Long, value: Boolean) {
        if (value == playing) return
        playing = value
        if (value && inside) activeUntil = now + IDLE_MS
    }

    /** Something that must keep the controls up (a drag, a resize, an open menu) begins. */
    fun lock() {
        locks++
    }

    /** What [lock] began has ended; the controls stay [IDLE_MS] more when the pointer is over the window, else they go within [LEAVE_MS]. */
    fun unlock(now: Long) {
        if (locks == 0) return
        locks--
        if (locks == 0) {
            activeUntil = now + IDLE_MS
            leaveUntil = now + LEAVE_MS
        }
    }

    companion object {
        /** How long the controls stay after the last movement or click over a playing window. */
        const val IDLE_MS = 1_500L

        /** How long after the pointer left they go (short, so a brush across the edge does not flicker them). */
        const val LEAVE_MS = 250L

        /** How long a window that has just opened shows them. */
        const val OPEN_MS = 1_500L
    }
}

/**
 * Whether the pointer at ([x], [y]) (screen pixels, or null when it cannot be read) is over the window at ([left], [top]) of
 * [width] x [height]: the authority for hover, because an always-on-top window that does not have the focus often misses the enter and
 * exit events of its own components.
 */
internal fun pointerOverWindow(x: Int?, y: Int?, left: Int, top: Int, width: Int, height: Int): Boolean =
    x != null && y != null && x >= left && x < left + width && y >= top && y < top + height
