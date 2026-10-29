package com.cosmotune.video.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/** How Video mode smooths a low frame rate: not at all, by cross-fading neighbours, or by moving the picture along its motion. */
enum class SmoothMode { OFF, BLEND, SMOOTH }

/** What kind of picture the motion search is tuned for; [AUTO] looks at the video and picks. */
enum class SmoothPreset { AUTO, FILM, ANIME }

/**
 * Frame smoothing (the desktop app, and the phone's "match the display's refresh rate" switch). Everything defaults to what Video
 * mode did before: [mode] is off, so frames reach the screen untouched.
 *
 * - [targetFps] is the rate the smoothed picture is drawn at: 0 for the display's own, else one of [TARGET_CHOICES]; a display
 *   cannot show more than its refresh rate, and what is truly drawn is what the readout reports.
 * - [strength] (0 to 100): for Blend, how soft the cross-fade is (0 switches frames at the midpoint, 100 fades linearly); for
 *   Smooth, how much of a doubtful match is still trusted.
 * - [onlyBelow30]: leave videos of 30 fps and more alone.
 * - [matchDisplayRate]: the phone asks its screen to switch to the video's frame rate when it can do so without a flicker.
 */
data class SmoothnessSettings(
    val mode: SmoothMode = SmoothMode.OFF,
    val targetFps: Int = 0,
    val strength: Int = DEFAULT_STRENGTH,
    val onlyBelow30: Boolean = true,
    val preset: SmoothPreset = SmoothPreset.AUTO,
    val matchDisplayRate: Boolean = true,
) {
    companion object {
        /** Match the display, then the fixed rates. */
        val TARGET_CHOICES: List<Int> = listOf(0, 48, 60, 90, 120)

        const val DEFAULT_STRENGTH = 50

        /** A video below this many frames a second counts as "below 30" (29.97 is a 30 fps video). */
        const val SLOW_SOURCE_FPS = 29.5f
    }
}

/**
 * What the engine truly does with frames right now, for the player's status chip: the [mode] in effect (which may be lower than
 * the one asked for), the frame rate measured from the frames the player delivers ([sourceFps]), the rate the picture is
 * really drawn at ([presentedFps]), and a [notice] when smoothing was refused or stepped down ("Smooth was too slow, so Blend is
 * used"). A rate that is not measured yet is null, and then there is no [label].
 */
data class SmoothnessReadout(
    val mode: SmoothMode = SmoothMode.OFF,
    val sourceFps: Float? = null,
    val presentedFps: Float? = null,
    val notice: String? = null,
) {
    /** "24 -> 60 fps . Smooth" while frames are really being made, else null: a conversion that is not happening is never written. */
    fun label(arrow: String = "→", dot: String = "·"): String? {
        val from = sourceFps ?: return null
        val to = presentedFps ?: return null
        if (mode == SmoothMode.OFF || to < from * 1.05f) return null
        val name = if (mode == SmoothMode.SMOOTH) "Smooth" else "Blend"
        return "${fpsText(from)} $arrow ${fpsText(to)} fps $dot $name"
    }

    companion object {
        val None = SmoothnessReadout()

        /** A frame rate as people write it: 23.976 is "24", 59.94 is "60", 24.5 stays "24.5". */
        fun fpsText(fps: Float): String {
            val nearest = fps.roundToInt()
            return if (abs(fps - nearest) < 0.06f * maxOf(1f, nearest / 24f)) nearest.toString() else ((fps * 10).roundToInt() / 10f).toString()
        }
    }
}
