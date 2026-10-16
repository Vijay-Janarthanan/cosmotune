package com.cosmotune.video.player.controller

/**
 * The clock of history (spec 6.1: "every 10 s while playing"): call [restart] when playing begins or resumes and [poll] at
 * every tick of the controller; [poll] says yes once per [intervalMs] of playing and starts the next interval. Not
 * thread-safe by itself: the controller calls it under its own lock.
 */
class HistoryTicker(val intervalMs: Long = INTERVAL_MS, private val clock: () -> Long) {
    private var lastMs = clock()

    /** Starts counting the interval from now. */
    fun restart() {
        lastMs = clock()
    }

    /** True when [intervalMs] have passed since the last [restart] or the last yes; the next interval starts then. */
    fun poll(): Boolean {
        val now = clock()
        if (now - lastMs < intervalMs) return false
        lastMs = now
        return true
    }

    companion object {
        /** History is written every 10 s of playing (spec 6.1). */
        const val INTERVAL_MS = 10_000L
    }
}

/**
 * Auto's step down (spec 6.3: "Auto steps down one rung when more than 10 % of frames drop over 10 s"). [reset] opens a
 * window at the engine's current dropped-frame count; [check] closes a window that has run [windowMs] and says whether more
 * than [threshold] of the frames that should have been shown (the frame rate times the seconds times the speed) were
 * dropped, then opens the next window. Not thread-safe by itself: the controller calls it under its own lock.
 */
class FrameDropWatch(
    private val windowMs: Long = WINDOW_MS,
    private val threshold: Double = THRESHOLD,
    private val clock: () -> Long,
) {
    private var startMs = clock()
    private var baseline = 0

    /** Opens a window now; [droppedFrames] is the engine's count at this moment. */
    fun reset(droppedFrames: Int) {
        startMs = clock()
        baseline = droppedFrames
    }

    /** False until the window is over; then whether the frames dropped in it exceeded the threshold (and a new window opens). */
    fun check(droppedFrames: Int, fps: Int, speed: Float): Boolean {
        val now = clock()
        val elapsed = now - startMs
        if (elapsed < windowMs) return false
        val dropped = droppedFrames - baseline
        startMs = now
        baseline = droppedFrames
        val shown = fps * (elapsed / 1000.0) * speed
        return shown > 0 && dropped > shown * threshold
    }

    companion object {
        /** The window of spec 6.3: 10 s. */
        const val WINDOW_MS = 10_000L

        /** More than 10 % of the frames. */
        const val THRESHOLD = 0.10
    }
}
