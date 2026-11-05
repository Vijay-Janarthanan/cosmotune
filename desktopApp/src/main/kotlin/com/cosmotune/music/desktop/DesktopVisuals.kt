package com.cosmotune.music.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.cosmotune.music.data.audio.AudioVisuals
import com.cosmotune.music.data.audio.VisualAudioFrame

internal typealias DesktopVisualFrame = VisualAudioFrame

/** The desktop and Android feed the same read-only view; no sampling while it is closed. */
internal object DesktopVisualAudio {
    fun publish(samples: FloatArray, count: Int, gain: Float) = AudioVisuals.publish(samples, count, gain)
}

internal fun visualFrame(samples: FloatArray, count: Int, gain: Float): DesktopVisualFrame = AudioVisuals.sample(samples, count, gain)

/** Retain the desktop's full-screen window while sharing its controls and scenes with Android. */
@Composable
internal fun DesktopVisualsPresentation(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Window(
        onCloseRequest = onDismiss, title = "Cosmotune · Visuals",
        state = rememberWindowState(placement = WindowPlacement.Fullscreen), undecorated = true,
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onDismiss(); true } else false
        },
    ) { content() }
}
