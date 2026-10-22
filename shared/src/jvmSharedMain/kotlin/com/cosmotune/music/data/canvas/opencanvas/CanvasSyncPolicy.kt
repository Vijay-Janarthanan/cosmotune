package com.opencanvas.core.sync

import kotlin.math.abs

/**
 * Tunable constants shared by every host that drives a [CanvasSyncPolicy].
 */
object CanvasSyncDefaults {
    /** Recommended period, in milliseconds, between two [CanvasSyncPolicy.decide] calls. */
    const val CHECK_INTERVAL_MS = 250L

    /** Default drift, in milliseconds, below which the video is considered in sync. */
    const val TOLERANCE_MS = 150L

    /** Default drift, in milliseconds, above which the video is re-positioned with a hard seek. */
    const val HARD_SEEK_MS = 500L

    /**
     * Song-position jump, in milliseconds, beyond what wall time explains,
     * above which [CanvasSyncPolicy.seekDetected] reports a seek.
     */
    const val SEEK_JUMP_MS = 1000L

    /**
     * Minimum confidence of a measured offset for it to be trusted. Offsets below this are
     * discarded by the resolver (the track then carries `audioOffsetMs = 0` and
     * `syncConfidence = 0.0`), and hosts should show the still artwork instead of the video.
     */
    const val MIN_SYNC_CONFIDENCE = 0.4
}

/**
 * What the host should do to the video player right now. Produced by [CanvasSyncPolicy.decide].
 *
 * The policy only governs the video *position and speed*. Hosts additionally pause the video
 * whenever the song is paused and resume it when the song plays.
 */
sealed interface CanvasSyncAction {

    /**
     * The song position maps to before the start of the video (`target < 0`, typically a negative
     * offset). Show the still artwork and keep the video idle at 0.
     */
    data object Hidden : CanvasSyncAction

    /**
     * Do nothing: the video is not ready yet, or the song is paused and the video is already
     * positioned within tolerance of the target.
     */
    data object Hold : CanvasSyncAction

    /** Hard-seek the video player to [videoMs] (always inside the video). */
    data class SeekTo(val videoMs: Long) : CanvasSyncAction

    /**
     * Keep playing, setting the video playback speed to [speed] (`1.0f` when in sync). Speeds
     * differ from `1.0f` by at most `0.05f`, which is imperceptible but absorbs small drift.
     */
    data class Nudge(val speed: Float) : CanvasSyncAction

    /**
     * The song position maps to or past the end of the video. The canvas never loops: hold the
     * last frame or show the still artwork until the next track starts.
     */
    data object Ended : CanvasSyncAction
}

/**
 * Pure, platform-independent brain that keeps a canvas video locked to the song being played.
 *
 * The relationship is `videoTimeMs = songTimeMs + offsetMs` inside each segment of a [SyncMap]
 * (see [com.opencanvas.core.models.OpenCanvasTrack.audioOffsetMs]); a music video that is an edit
 * of the song has several segments, and the policy hard-seeks across every edit point. The video never loops: when the
 * mapped position falls outside `[0, videoDurationMs)` the host shows the still artwork.
 *
 * Typical host loop (every [CanvasSyncDefaults.CHECK_INTERVAL_MS], and immediately whenever
 * [seekDetected] returns true or the track changes):
 *
 * ```
 * when (val action = policy.decide(songMs, videoMs, songPlaying, videoReady)) {
 *     Hidden -> showArtwork()
 *     Hold -> Unit
 *     Ended -> showArtwork() // or hold the last frame
 *     is SeekTo -> video.seekTo(action.videoMs)
 *     is Nudge -> video.setSpeed(action.speed)
 * }
 * ```
 *
 * On a track change create a new policy from the new track; a new canvas starts immediately.
 *
 * @property syncMap Measured map from song time to video time (segments with their offsets).
 * @property videoDurationMs Video length in milliseconds; `<= 0` means the end is unknown.
 * @property toleranceMs Drift up to which the video is considered in sync.
 * @property hardSeekMs Drift beyond which the video is hard-seeked instead of nudged;
 *   must be `>= toleranceMs`.
 */
class CanvasSyncPolicy(
    val syncMap: SyncMap,
    val videoDurationMs: Long,
    val toleranceMs: Long = CanvasSyncDefaults.TOLERANCE_MS,
    val hardSeekMs: Long = CanvasSyncDefaults.HARD_SEEK_MS,
) {
    /** A video that is the song shifted by one constant [offsetMs] for its whole length. */
    constructor(
        offsetMs: Long,
        videoDurationMs: Long,
        toleranceMs: Long = CanvasSyncDefaults.TOLERANCE_MS,
        hardSeekMs: Long = CanvasSyncDefaults.HARD_SEEK_MS,
    ) : this(SyncMap.constant(offsetMs), videoDurationMs, toleranceMs, hardSeekMs)

    init {
        require(toleranceMs >= 0L) { "toleranceMs must be >= 0, was $toleranceMs" }
        require(hardSeekMs >= toleranceMs) {
            "hardSeekMs ($hardSeekMs) must be >= toleranceMs ($toleranceMs)"
        }
    }

    /** The offset of the longest segment of [syncMap] (the only offset, for a constant map). */
    val offsetMs: Long get() = syncMap.primaryOffsetMs

    /**
     * Video position that matches [songPositionMs]: `songPositionMs + offset`, with the offset of
     * the segment in force there (the nearest one where the song has no picture, so the number is
     * always defined). The result is not clamped and may be negative or beyond the video duration.
     */
    fun targetVideoMs(songPositionMs: Long): Long = songPositionMs + syncMap.offsetNear(songPositionMs)

    /**
     * Whether the video should be on screen at [songPositionMs]: the map has a segment there and the
     * target lies within `[0, videoDurationMs)`. When [videoDurationMs] is `<= 0` the end is unknown and only the
     * start boundary applies.
     */
    fun isVisible(songPositionMs: Long): Boolean {
        val offset = syncMap.offsetAt(songPositionMs) ?: return false // no matching picture here
        val target = songPositionMs + offset
        return target >= 0L && (videoDurationMs <= 0L || target < videoDurationMs)
    }

    /**
     * Decides what the host should do to the video player.
     *
     * Rules, in priority order (`target = targetVideoMs(songPositionMs)`,
     * `drift = videoPositionMs - target`, so positive drift means the video is ahead):
     *
     * 1. `target < 0` -> [CanvasSyncAction.Hidden].
     * 2. `target >= videoDurationMs` (known duration) -> [CanvasSyncAction.Ended].
     * 3. `!videoReady` -> [CanvasSyncAction.Hold] (visibility is decided first so the artwork
     *    shows regardless of buffering).
     * 4. Song paused: [CanvasSyncAction.SeekTo] the target when `|drift| > toleranceMs`,
     *    otherwise [CanvasSyncAction.Hold]. The host keeps the video paused.
     * 5. Song playing: `|drift| <= toleranceMs` -> `Nudge(1.0f)`;
     *    `|drift| <= hardSeekMs` -> `Nudge(speed)` with
     *    `speed = 1 - clamp(drift / 4000, -0.05, 0.05)` (video ahead -> slow down, behind ->
     *    speed up); otherwise [CanvasSyncAction.SeekTo] the target.
     *
     * @param songPositionMs Current position of the song audio.
     * @param videoPositionMs Current position of the video player.
     * @param songPlaying Whether the song is currently playing (not paused).
     * @param videoReady Whether the video player has a decodable frame and accepts commands.
     */
    fun decide(
        songPositionMs: Long,
        videoPositionMs: Long,
        songPlaying: Boolean,
        videoReady: Boolean,
    ): CanvasSyncAction {
        // Between segments the song has no matching picture; at the edge of a segment the target jumps
        // by the change of offset, which the drift check below turns into a hard seek.
        val offset = syncMap.offsetAt(songPositionMs) ?: return CanvasSyncAction.Hidden
        val target = songPositionMs + offset
        if (target < 0L) return CanvasSyncAction.Hidden
        if (videoDurationMs > 0L && target >= videoDurationMs) return CanvasSyncAction.Ended
        if (!videoReady) return CanvasSyncAction.Hold

        // a video whose music runs at another speed than the song's has to play at that speed to keep up
        val rate = syncMap.rateAt(songPositionMs)
        val drift = videoPositionMs - target
        val absDrift = abs(drift)
        return when {
            !songPlaying ->
                if (absDrift > toleranceMs) CanvasSyncAction.SeekTo(target) else CanvasSyncAction.Hold
            absDrift <= toleranceMs -> CanvasSyncAction.Nudge(rate.toFloat())
            absDrift <= hardSeekMs -> CanvasSyncAction.Nudge((rate * nudgeSpeed(drift)).toFloat())
            else -> CanvasSyncAction.SeekTo(target)
        }
    }

    /**
     * Whether the song position jumped by more than [CanvasSyncDefaults.SEEK_JUMP_MS] beyond what
     * wall-clock time explains, meaning the user scrubbed or skipped. Hosts use it to run
     * [decide] immediately instead of waiting for the next periodic check.
     *
     * The expected advance is [elapsedWallMs] when [playing] and `0` when paused. A `true`
     * result only means "re-evaluate now"; a false positive (for example a pause/resume inside a
     * long interval) is harmless because [decide] still compares against the real video position.
     *
     * @param prevSongMs Song position at the previous check.
     * @param songMs Song position now.
     * @param elapsedWallMs Wall-clock time since the previous check; negatives are treated as 0.
     * @param playing Whether the song was playing over that interval.
     */
    fun seekDetected(
        prevSongMs: Long,
        songMs: Long,
        elapsedWallMs: Long,
        playing: Boolean,
    ): Boolean {
        val expectedAdvance = if (playing) elapsedWallMs.coerceAtLeast(0L) else 0L
        val unexplained = (songMs - prevSongMs) - expectedAdvance
        return abs(unexplained) > CanvasSyncDefaults.SEEK_JUMP_MS
    }

    private fun nudgeSpeed(driftMs: Long): Float =
        1.0f - (driftMs / NUDGE_DIVISOR_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)

    private companion object {
        const val NUDGE_DIVISOR_MS = 4000f
        const val MAX_NUDGE = 0.05f
    }
}
