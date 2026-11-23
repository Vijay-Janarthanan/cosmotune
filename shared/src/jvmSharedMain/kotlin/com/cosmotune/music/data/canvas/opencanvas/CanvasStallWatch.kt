package com.opencanvas.core.sync

/**
 * Notices a music video that has stopped while its song plays on: no new frame for [stallMs] while one is due. A stream
 * whose signed address has run out, a range request refused (HTTP 403), a connection that stalls or a decoder that has run
 * dry all look the same on screen, a frozen picture, and none of them says so by itself. The host asks [stalled] every
 * check, reopens the stream at [reopenAtMs] when it is, and after [maxReopens] failed reopens shows the cover instead of
 * the frozen frame, trying again every [retryAfterMs] (doubling) for as long as the song plays.
 *
 * Pure and clock-free: every call takes the time, so it is tested without waiting.
 */
class CanvasStallWatch(
    val stallMs: Long = DEFAULT_STALL_MS,
    /** After opening or seeking the first frame may take this long: the bytes at the new place are fetched first. */
    val settleMs: Long = DEFAULT_SETTLE_MS,
    val maxReopens: Int = DEFAULT_MAX_REOPENS,
    val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
) {
    private var lastFrameAtMs = Long.MIN_VALUE
    private var dueBy = Long.MAX_VALUE
    private var failedReopens = 0
    private var nextTryAtMs = Long.MIN_VALUE

    /** Reopens in a row that brought no frame; 0 once a frame comes. */
    val failures: Int get() = failedReopens

    /** True once [maxReopens] reopens in a row have failed: the cover shows until a later try brings a frame. */
    val givenUp: Boolean get() = failedReopens >= maxReopens

    /** A frame has just been shown. */
    fun framePresented(nowMs: Long) {
        lastFrameAtMs = nowMs
        dueBy = nowMs + stallMs
        failedReopens = 0
        nextTryAtMs = Long.MIN_VALUE
    }

    /** The video was opened again or moved to another place: its next frame has [settleMs] to come. */
    fun repositioned(nowMs: Long) {
        dueBy = nowMs + settleMs
    }

    /** No frame is due (the song is paused, the video is out of sight or outside the map): nothing can stall meanwhile. */
    fun idle() {
        dueBy = Long.MAX_VALUE
    }

    /** Whether the video has stalled at [nowMs]: a frame was due and none came. */
    fun stalled(nowMs: Long): Boolean = nowMs >= dueBy

    /** How long since the last frame, for the log; null before the first one. */
    fun sinceLastFrameMs(nowMs: Long): Long? = if (lastFrameAtMs == Long.MIN_VALUE) null else nowMs - lastFrameAtMs

    /** A reopen brought no frame. */
    fun reopenFailed(nowMs: Long) {
        failedReopens++
        nextTryAtMs = if (givenUp) nowMs + (retryAfterMs shl (failedReopens - maxReopens).coerceIn(0, 4)) else nowMs
        dueBy = Long.MAX_VALUE
    }

    /** Whether to try reopening at [nowMs]: at once while under [maxReopens], then only after the back-off. */
    fun mayReopen(nowMs: Long): Boolean = nowMs >= nextTryAtMs

    companion object {
        const val DEFAULT_STALL_MS = 1_500L
        const val DEFAULT_SETTLE_MS = 5_000L
        const val DEFAULT_MAX_REOPENS = 3
        const val DEFAULT_RETRY_AFTER_MS = 5_000L

        /**
         * Where the reopened video starts: the place the map gives for the song's position now, never before the start. A video
         * that runs at another speed than the song ([SyncSegment.rate]) is placed by the same map, so it lines up as before.
         */
        fun reopenAtMs(policy: CanvasSyncPolicy, songMs: Long): Long = policy.targetVideoMs(songMs).coerceAtLeast(0L)

        /**
         * Why a video stalled, in a few words for the log: what the network reader said when it stopped ([failure]), whether
         * the video was read over the network at all ([streaming]) and whether its signed address had run out ([expired]).
         * Addresses are left out: they are signed.
         */
        fun reason(failure: String?, streaming: Boolean, expired: Boolean): String {
            val said = failure?.replace(Regex("https?://\\S+"), "<address>")
            val ranOut = if (expired) ", its address has run out" else ""
            return when {
                said != null && "403" in said -> "the stream refused a range (HTTP 403)$ranOut"
                said != null -> "the stream stopped: $said$ranOut"
                expired -> "the stream's address has run out"
                streaming -> "no bytes from the stream in time: a slow or stalled connection"
                else -> "the decoder gave no frame"
            }
        }
    }
}
