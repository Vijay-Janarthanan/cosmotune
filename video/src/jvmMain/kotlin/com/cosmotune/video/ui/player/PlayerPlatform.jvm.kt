package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cosmotune.video.player.VideoPlayback

/** Desktop: no brightness; the volume is the player's own, kept for the session (spec 6.6 Up and Down). */
@Composable
actual fun rememberPlayerPlatform(playback: VideoPlayback): PlayerPlatformControls =
    remember(playback) { DesktopPlayerPlatform(playback) }

private class DesktopPlayerPlatform(private val playback: VideoPlayback) : PlayerPlatformControls {
    override val brightnessSupported: Boolean get() = false
    override val swipeControls: Boolean get() = false

    override fun brightness(): Float = 1f

    override fun setBrightness(value: Float) = Unit

    override fun volume(): Float = SessionVolume.value

    override fun setVolume(value: Float) {
        SessionVolume.value = value.coerceIn(0f, 1f)
        playback.engine.setVolume(SessionVolume.value)
    }
}

/** The desktop player's volume for the session: one value for every box, so the full window keeps what the page set. */
private object SessionVolume {
    @Volatile
    var value: Float = 1f
}
