package com.cosmotune.video.player

/**
 * The live window (plan task Y-28; spec 5.13, 6.2), pure. The engines report a live stream's duration as the END of its seekable
 * window (Media3: the window's duration with the position inside it; libmpv: the end of what it has read) and the window starts at
 * [WINDOW_START_MS]; at live they play [EDGE_OFFSET_MS] behind that end (spec 6.2: "10 s behind the live edge"), and the end itself
 * moves in steps of a segment. So the LIVE EDGE here is the place live plays: the window's end less the offset. Measured from it a
 * stream at live reads about 0 whatever the engine's jitter of up to a segment, "Go to live" shows only when the viewer went back
 * more than [GO_LIVE_AFTER_MS], and a seek to the edge lands where live plays with a buffer ahead of it.
 *
 * An upcoming video never reaches the player (spec 5.13): the controller's state for it is `UNAVAILABLE` with the premiere as its
 * item ([isUpcoming]); the start time is the item's `uploadedAtMs` ([upcomingStartMs]).
 */
object LiveWindow {
    /** How far behind the end of the window the engines play a live stream (spec 6.2; Media3's live target offset). */
    const val EDGE_OFFSET_MS = 10_000L

    /** "Go to live" shows when the playhead is more than this far behind the live edge (spec 5.13). */
    const val GO_LIVE_AFTER_MS = 10_000L

    /** Where a live window starts on both engines: Media3 counts the position inside the window, libmpv from where it joined. */
    const val WINDOW_START_MS = 0L

    /**
     * The live edge of a window that starts at [windowStartMs] and ends at [durationMs]: the end less [EDGE_OFFSET_MS], never before
     * the start. A window no longer than the offset (or none: a duration of 0) has its edge at its start: nothing to seek in.
     */
    fun liveEdgeMs(durationMs: Long, windowStartMs: Long = WINDOW_START_MS): Long =
        if (durationMs <= windowStartMs) windowStartMs else maxOf(windowStartMs, durationMs - EDGE_OFFSET_MS)

    /** How far [positionMs] is behind the live edge of a window ending at [durationMs]; 0 at or past the edge, and with no window. */
    fun behindLiveEdgeMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= WINDOW_START_MS) return 0
        return (liveEdgeMs(durationMs) - positionMs).coerceAtLeast(0)
    }

    /** Whether "Go to live" shows: more than 10 s behind the live edge (spec 5.13). */
    fun showGoLive(behindMs: Long): Boolean = behindMs > GO_LIVE_AFTER_MS

    /**
     * A seek to [targetMs] kept inside the window (spec 5.13: "seeks only within the stream's window"): not before [windowStartMs]
     * (0 at the least) and not past the live edge, where the buffer ends. With no window every target lands on its start.
     */
    fun clampSeek(targetMs: Long, windowStartMs: Long, durationMs: Long): Long {
        val start = windowStartMs.coerceAtLeast(0)
        return targetMs.coerceIn(start, liveEdgeMs(durationMs, start))
    }

    /** Whether [state] is an upcoming video's: `UNAVAILABLE` with a premiere as its item (spec 5.13: "Upcoming" and the start time). */
    fun isUpcoming(state: PlaybackUiState): Boolean = state.error == VideoUiError.UNAVAILABLE && state.item?.isUpcoming == true

    /** The start time of the upcoming video [state] shows (epoch milliseconds), or null when it is not one or the source gave none. */
    fun upcomingStartMs(state: PlaybackUiState): Long? = if (isUpcoming(state)) state.item?.uploadedAtMs else null
}
