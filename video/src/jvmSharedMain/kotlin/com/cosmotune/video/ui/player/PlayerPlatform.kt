package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.cosmotune.video.player.VideoPlayback

/**
 * What the chrome changes on the device besides the video (spec 6.5 "Full-screen gestures", 6.6 Up and Down): the screen's
 * brightness and the volume, both on a 0..1 scale.
 *
 * - Android: the window's own brightness (put back to the system's when the box that changed it leaves) and the music stream's
 *   volume; [swipeControls] is true, so full screen offers the vertical drags.
 * - Desktop: no brightness ([brightnessSupported] false); the volume is the player's own (`VideoPlayerEngine.setVolume`), kept for
 *   the session; no swipe controls.
 */
@Stable
interface PlayerPlatformControls {
    /** Whether [setBrightness] does anything here. */
    val brightnessSupported: Boolean

    /** Whether full screen takes the vertical drags of brightness and volume (a touch screen). */
    val swipeControls: Boolean

    /** The brightness now, 0..1. */
    fun brightness(): Float

    /** Sets the brightness, 0..1. */
    fun setBrightness(value: Float)

    /** The volume now, 0..1. */
    fun volume(): Float

    /** Sets the volume, 0..1. */
    fun setVolume(value: Float)
}

/** The device's controls for the box of [playback] (see [PlayerPlatformControls]). */
@Composable
expect fun rememberPlayerPlatform(playback: VideoPlayback): PlayerPlatformControls
