package com.cosmotune.video.player.smooth

import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothPreset
import com.cosmotune.video.settings.SmoothnessReadout
import com.cosmotune.video.settings.SmoothnessSettings
import kotlin.math.floor

/**
 * The pure decisions of frame smoothing, none of which touch a window, a thread or a clock of their own: what the settings mean
 * for the motion search and the cross-fade, when smoothing is in effect, where in the gap between two source frames a drawn
 * frame falls, when a frame is drawn at a chosen rate, and when a mode that is too slow steps down.
 */
object SmoothPolicy {

    /** The tuning of the motion search for a preset ([SmoothPreset.AUTO] must be resolved first) and a strength of 0 to 100. */
    fun motionParams(preset: SmoothPreset, strength: Int): MotionParams {
        val s = strength.coerceIn(0, 100) / 100f
        val tolerance = 6f + 20f * s
        return when (preset) {
            // Flat colour and large jumps between drawn poses: search further, and trust a worse match a little more.
            SmoothPreset.ANIME -> MotionParams(range = 12, tolerance = tolerance + 4f, cutFraction = 0.6f, cutMad = 32f)
            else -> MotionParams(range = 8, tolerance = tolerance, cutFraction = 0.6f, cutMad = 28f)
        }
    }

    /**
     * The weight of the newer frame at [phase] (0 to 1) in a cross-fade of softness [strength] (0 to 100): 100 is the straight
     * line, lower values keep the nearer frame longer and change over faster around the middle, 0 switches at the middle.
     */
    fun blendWeight(phase: Float, strength: Int): Float {
        if (strength <= 0) return if (phase < 0.5f) 0f else 1f
        val s = (strength.coerceIn(0, 100) / 100f).coerceAtLeast(MIN_SOFTNESS)
        return ((phase - 0.5f) / s + 0.5f).coerceIn(0f, 1f)
    }

    /** How strongly a pixel that the two warped frames disagree on is taken from the nearer frame alone (0 to 1), by strength. */
    fun mismatchBias(strength: Int): Float = 0.55f + 0.4f * (1f - strength.coerceIn(0, 100) / 100f)

    /**
     * The mode in effect: [settings]' mode if the video is known to be slow enough to need it (see
     * [SmoothnessSettings.onlyBelow30]) and not already at the drawn rate, capped by what the [governorCeiling] allows. An
     * unmeasured video ([sourceFps] null) is not smoothed: nothing is assumed.
     */
    fun effectiveMode(settings: SmoothnessSettings, sourceFps: Float?, outputFps: Float, governorCeiling: SmoothMode): SmoothMode {
        if (settings.mode == SmoothMode.OFF || sourceFps == null) return SmoothMode.OFF
        if (settings.onlyBelow30 && sourceFps >= SmoothnessSettings.SLOW_SOURCE_FPS) return SmoothMode.OFF
        // Nothing to fill in when the screen shows no more than the video has.
        if (outputFps < sourceFps * 1.2f) return SmoothMode.OFF
        return if (settings.mode.ordinal <= governorCeiling.ordinal) settings.mode else governorCeiling
    }

    /** The reason smoothing is not used for a video, for the settings page; null when it is used or not asked for. */
    fun whyNot(settings: SmoothnessSettings, sourceFps: Float?, outputFps: Float, mode: SmoothMode): String? = when {
        settings.mode == SmoothMode.OFF || mode != SmoothMode.OFF || sourceFps == null -> null
        settings.onlyBelow30 && sourceFps >= SmoothnessSettings.SLOW_SOURCE_FPS ->
            "This video is already ${SmoothnessReadout.fpsText(sourceFps)} fps, so it is left as it is."
        outputFps < sourceFps * 1.2f ->
            "This screen shows ${SmoothnessReadout.fpsText(outputFps)} frames a second at most, no more than the video has."
        else -> null
    }

    /**
     * Where [nowNanos] falls in the gap that ends with the newer frame of a pair: 0 is the older frame, 1 the newer. The newer
     * frame arrived at [newerAtNanos]; the next is expected [intervalNanos] later (the measured gap, steadier than this pair's own).
     * A pair too far apart or too close to be consecutive frames of one smooth run (a seek, a stall, a frame shown twice) is
     * not interpolated: it is 1 at once.
     */
    fun phase(nowNanos: Long, newerAtNanos: Long, pairGapNanos: Long, intervalNanos: Long): Float {
        if (intervalNanos <= 0 || pairGapNanos > intervalNanos * MAX_PAIR_GAP || pairGapNanos < intervalNanos / MAX_PAIR_GAP) return 1f
        return ((nowNanos - newerAtNanos).toFloat() / intervalNanos).coerceIn(0f, 1f)
    }

    /** Whether the frame is drawn at all after [lastIndex] (see [outputIndex]); a display-rate target draws on every call. */
    fun outputIndex(nowNanos: Long, targetFps: Int): Long =
        if (targetFps <= 0) nowNanos else floor(nowNanos.toDouble() * targetFps / 1_000_000_000.0).toLong()

    /** The ceiling smoothing can be drawn at: the target when one is chosen (never above the display), else the display's rate. */
    fun outputFps(settings: SmoothnessSettings, displayHz: Float): Float =
        if (settings.targetFps <= 0) displayHz else minOf(settings.targetFps.toFloat(), displayHz)

    /** A pair further apart than this many intervals (or closer than the inverse) is not interpolated. */
    private const val MAX_PAIR_GAP = 2.5

    private const val MIN_SOFTNESS = 0.05f
}

/**
 * Steps smoothing down when it cannot keep up. Every piece of work (a motion search, a frame's draw) is reported with the time it
 * took and the time it had; once [slowLimit] reports in a row, or half of the last [window], were too slow, the ceiling drops one
 * step: Smooth to Blend to off. A notice says so. [reset] (a new video, the person's own change) puts the ceiling back.
 */
class SmoothGovernor(private val slowLimit: Int = 6, private val window: Int = 24) {
    private val recent = BooleanArray(window)
    private var filled = 0
    private var cursor = 0
    private var slowInRow = 0

    /** The highest mode allowed now. */
    @Volatile var ceiling: SmoothMode = SmoothMode.SMOOTH
        private set

    /** What was said last time the ceiling dropped, or null. */
    @Volatile var notice: String? = null
        private set

    /** Reports one piece of work; true when this report lowered the ceiling. */
    @Synchronized
    fun report(workMs: Double, budgetMs: Double): Boolean {
        if (ceiling == SmoothMode.OFF) return false
        val slow = workMs > budgetMs
        recent[cursor] = slow
        cursor = (cursor + 1) % window
        if (filled < window) filled++
        slowInRow = if (slow) slowInRow + 1 else 0
        val slowCount = (0 until filled).count { recent[it] }
        if (slowInRow < slowLimit && slowCount < window / 2) return false
        ceiling = if (ceiling == SmoothMode.SMOOTH) SmoothMode.BLEND else SmoothMode.OFF
        notice = if (ceiling == SmoothMode.BLEND) "Smooth was too slow for this video on this computer, so Blend is used instead."
        else "Blend was too slow for this video on this computer, so frame smoothing is switched off."
        filled = 0
        cursor = 0
        slowInRow = 0
        return true
    }

    /** Switches smoothing off for good (until [reset]), saying why: an error the pipeline cannot work through. */
    @Synchronized
    fun stop(reason: String) {
        ceiling = SmoothMode.OFF
        notice = reason
    }

    @Synchronized
    fun reset() {
        ceiling = SmoothMode.SMOOTH
        notice = null
        filled = 0
        cursor = 0
        slowInRow = 0
    }
}

/**
 * Tells a still-picture-heavy video (drawn animation, which shows each drawing for several frames) from filmed motion, for
 * [SmoothPreset.AUTO]: the share of the last [window] pairs in which nothing moved at all.
 */
class PresetResolver(private val window: Int = 48) {
    private val stills = BooleanArray(window)
    private var filled = 0
    private var cursor = 0

    @Synchronized
    fun note(still: Boolean) {
        stills[cursor] = still
        cursor = (cursor + 1) % window
        if (filled < window) filled++
    }

    /** [preset] itself, or for AUTO: anime when many pairs repeat the picture (after at least 24 pairs), else film. */
    @Synchronized
    fun resolve(preset: SmoothPreset): SmoothPreset {
        if (preset != SmoothPreset.AUTO) return preset
        if (filled < MIN_PAIRS) return SmoothPreset.FILM
        val still = (0 until filled).count { stills[it] }
        return if (still.toFloat() / filled > ANIME_STILL_SHARE) SmoothPreset.ANIME else SmoothPreset.FILM
    }

    @Synchronized
    fun reset() {
        filled = 0
        cursor = 0
    }

    private companion object {
        const val MIN_PAIRS = 24
        const val ANIME_STILL_SHARE = 0.4f
    }
}
