package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.video.player.VideoPlayback

/**
 * Leaving the app from any page of the phone shell (spec 6.1 background): when the window stops, the playback is told the UI went
 * away, so the controller pauses the video when "Keep playing in the background" is off and goes audio-only when it is on (a Short
 * always pauses); when the window starts again it is told the UI is back. The watch page does the same for itself (Y-19's
 * `WatchLifecycle`), so the shell stays out while [onWatch]. The shell composes this only while picture-in-picture is not the
 * window: there Y-19's picture-in-picture layer decides (a closed picture pauses), and the observer is gone.
 */
@Composable
internal fun VideoBackgroundLifecycle(playback: VideoPlayback, onWatch: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val watch by rememberUpdatedState(onWatch)
    DisposableEffect(lifecycle, playback) {
        val observer = VideoBackgroundObserver(playback, onWatch = { watch })
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * What [VideoBackgroundLifecycle] does with the window's events: on stop, `onUiVisible(false)`; on start, `onUiVisible(true)`; neither
 * while the watch page is on show ([onWatch]). Only a start that follows a stop the shell reported says the UI is back, so opening
 * the mode (whose first event is a start) tells the playback nothing.
 */
internal class VideoBackgroundObserver(
    private val playback: VideoPlayback,
    private val onWatch: () -> Boolean,
) : LifecycleEventObserver {
    private var reportedAway = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = on(event)

    fun on(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_STOP -> if (!onWatch()) {
                reportedAway = true
                playback.onUiVisible(false)
            }
            Lifecycle.Event.ON_START -> if (reportedAway) {
                reportedAway = false
                if (!onWatch()) playback.onUiVisible(true)
            }
            else -> Unit
        }
    }
}
